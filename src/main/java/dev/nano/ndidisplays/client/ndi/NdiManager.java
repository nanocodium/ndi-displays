package dev.nano.ndidisplays.client.ndi;

import com.mojang.logging.LogUtils;
import me.walkerknapp.devolay.Devolay;
import me.walkerknapp.devolay.DevolayFinder;
import me.walkerknapp.devolay.DevolayReceiver;
import me.walkerknapp.devolay.DevolayRouter;
import me.walkerknapp.devolay.DevolaySource;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Client-side NDI runtime: initialises the NDI library, discovers sources on the
 * network, and owns one {@link NdiStream} per requested source name. Streams that
 * no wall has rendered for a while are shut down automatically.
 *
 * Reception is per-client on purpose — every viewer pulls the stream themselves,
 * exactly like every media server / processor on a real stage network would.
 */
public final class NdiManager {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final long STREAM_IDLE_TIMEOUT_MS = 15_000;

    private static volatile boolean initAttempted;
    private static volatile boolean available;
    private static volatile String status = "NDI not initialised";
    private static volatile List<String> sourceNames = List.of();

    private static DevolayFinder finder;
    private static Thread finderThread;
    private static final Map<String, NdiStream> streams = new ConcurrentHashMap<>();

    /** Kept short so a blocking discovery wait cannot stall receiver creation for long. */
    private static final int FIND_WAIT_MS = 500;

    /**
     * Serialises every call into the {@link DevolayFinder}, and guards the lifetime of the
     * {@link DevolaySource} handles it hands out.
     *
     * Two separate hazards need this one lock:
     *
     * 1. Devolay marks {@code getCurrentSources()} synchronized but leaves
     *    {@code waitForSources()} unsynchronized, so without an external lock
     *    {@code NDIlib_find_wait_for_sources} can run on the finder thread while
     *    {@code NDIlib_find_get_current_sources} runs on a receive thread. A single NDI
     *    find instance is not safe for concurrent calls, and the latter also frees the
     *    previous source array while the former may be mutating the same list.
     *
     * 2. {@code getCurrentSources()} calls {@code close()} — and therefore
     *    {@code deallocSource()} — on every handle it returned the previous time, so a
     *    source is only valid between one call and the next. Retaining those handles is a
     *    native use-after-free: {@code new DevolayReceiver(source, …)} reads
     *    {@code structPointer} directly with no closed check, so it would build a receiver
     *    from freed memory.
     *
     * So every read of a DevolaySource happens under this lock, and receivers are
     * constructed before it is released — a receiver copies the source's connection
     * details, so it stays valid once created.
     */
    private static final Object SOURCE_LOCK = new Object();

    private NdiManager() {
    }

    public static synchronized void ensureInit() {
        if (initAttempted) {
            return;
        }
        initAttempted = true;
        try {
            Devolay.loadLibraries();
            finder = new DevolayFinder();
            available = true;
            status = "NDI runtime loaded";
            finderThread = new Thread(NdiManager::runFinderLoop, "NDI-Finder");
            finderThread.setDaemon(true);
            finderThread.start();
            LOGGER.info("[ndidisplays] NDI runtime initialised: {}", ndiVersion());
        } catch (Throwable t) {
            available = false;
            status = "NDI runtime not found — install the NDI runtime (ndi.video/tools)";
            LOGGER.warn("[ndidisplays] Could not initialise NDI: {}", t.toString());
        }
    }

    private static void runFinderLoop() {
        while (true) {
            try {
                // Only the names escape this block — they are copied into Java strings
                // while the handles are still alive.
                List<String> names;
                synchronized (SOURCE_LOCK) {
                    finder.waitForSources(FIND_WAIT_MS);
                    DevolaySource[] found = finder.getCurrentSources();
                    names = new ArrayList<>(found.length);
                    for (DevolaySource src : found) {
                        names.add(src.getSourceName());
                    }
                }
                sourceNames = List.copyOf(names);
                Thread.sleep(1000);
            } catch (InterruptedException e) {
                return;
            } catch (Throwable t) {
                LOGGER.warn("[ndidisplays] NDI finder error: {}", t.toString());
                try {
                    Thread.sleep(5000);
                } catch (InterruptedException e) {
                    return;
                }
            }
        }
    }

    /** The loaded NDI runtime's own version string, for diagnosing runtime/binding mismatches. */
    private static String ndiVersion() {
        try {
            return Devolay.getNDIVersion();
        } catch (Throwable t) {
            return "version unavailable";
        }
    }

    public static boolean isAvailable() {
        ensureInit();
        return available;
    }

    public static String getStatus() {
        ensureInit();
        return status;
    }

    /** Names of all NDI sources currently visible on the network. */
    public static List<String> getSourceNames() {
        ensureInit();
        return sourceNames;
    }

    /**
     * Points an existing receiver at the source matching {@code name}. Exact match first,
     * then a case-insensitive substring match so "OBS" finds "STAGE-PC (OBS)".
     *
     * The receiver is retargeted with {@code connect()} rather than recreated: every JVM
     * crash observed on NDI 6 has been inside {@code NDIlib_recv_destroy}, so receivers
     * are built once per stream and live until the stream ends. The source handle is only
     * touched inside {@link #SOURCE_LOCK}, because the finder frees the previous poll's
     * handles on every new poll.
     *
     * @return false when NDI is unavailable or no source currently matches
     */
    static boolean connectReceiver(DevolayReceiver receiver, String name) {
        if (name == null || name.isBlank() || !isAvailable()) {
            return false;
        }
        String needle = name.toLowerCase(Locale.ROOT);
        synchronized (SOURCE_LOCK) {
            DevolaySource match = null;
            for (DevolaySource src : finder.getCurrentSources()) {
                String sourceName = src.getSourceName();
                if (sourceName.equals(name)) {
                    match = src;
                    break;
                }
                if (match == null && sourceName.toLowerCase(Locale.ROOT).contains(needle)) {
                    match = src;
                }
            }
            if (match == null) {
                return false;
            }
            receiver.connect(match);
            return true;
        }
    }

    /**
     * Points a router at the source matching {@code name}, or clears it when nothing
     * matches. Same handle-lifetime rule as {@link #connectReceiver}: the DevolaySource is
     * only touched inside {@link #SOURCE_LOCK}, because the finder frees the previous
     * poll's handles on every new poll.
     *
     * @return true when the router is now pointed at a live source
     */
    public static boolean routeTo(DevolayRouter router, String name) {
        if (router == null || !isAvailable()) {
            return false;
        }
        if (name == null || name.isBlank()) {
            router.clearSource();
            return false;
        }
        String needle = name.toLowerCase(Locale.ROOT);
        synchronized (SOURCE_LOCK) {
            DevolaySource match = null;
            for (DevolaySource src : finder.getCurrentSources()) {
                String sourceName = src.getSourceName();
                if (sourceName.equals(name)) {
                    match = src;
                    break;
                }
                if (match == null && sourceName.toLowerCase(Locale.ROOT).contains(needle)) {
                    match = src;
                }
            }
            if (match == null) {
                router.clearSource();
                return false;
            }
            router.setSource(match);
            return true;
        }
    }

    /** Called from renderers; returns null when NDI is unavailable or no name is set. */
    public static NdiStream acquire(String sourceName) {
        if (sourceName == null || sourceName.isBlank() || !isAvailable()) {
            return null;
        }
        NdiStream stream = streams.computeIfAbsent(sourceName, NdiStream::new);
        stream.touch();
        return stream;
    }

    /** Client tick: shut down streams nothing has rendered recently. */
    public static void tick() {
        if (streams.isEmpty()) {
            return;
        }
        long now = System.currentTimeMillis();
        streams.entrySet().removeIf(e -> {
            if (now - e.getValue().getLastUsed() > STREAM_IDLE_TIMEOUT_MS) {
                e.getValue().shutdown();
                return true;
            }
            return false;
        });
    }

    public static void shutdownAll() {
        streams.values().forEach(NdiStream::shutdown);
        streams.clear();
    }
}
