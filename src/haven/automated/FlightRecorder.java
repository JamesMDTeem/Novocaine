package haven.automated;

import haven.Console;

import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.text.SimpleDateFormat;
import java.util.Date;

/**
 * {@code :jfr} - a Java Flight Recorder recording of the running client, written to {@code logs/}.
 *
 * The stall watchdog (Log Diagnostics) names the method a frame was stuck in once it has been
 * stuck 400 ms; a recording names everything else. It samples every thread's stack all the time,
 * records every lock a thread waited for and how long, every allocation hot spot, every GC pause
 * and every file read - so a stutter that is many small costs rather than one big one, or a
 * worker pool all parked behind one monitor, shows up. That is how brodgar-io-client found the
 * RenderTree interner freeze ("parked 88 ms on a parallelStream whose workers were all inside
 * DepInfo interning"). The "profile" settings cost a few per cent while recording and nothing
 * otherwise.
 *
 * <pre>
 *   :jfr start [seconds]   start recording; with seconds, it stops and writes the file itself
 *   :jfr stop              stop and write logs/jfr-&lt;time&gt;.jfr
 *   :jfr                   say whether one is running
 * </pre>
 *
 * Read the file with the JDK's own {@code jfr} tool ({@code jfr print --events
 * jdk.JavaMonitorEnter file.jfr}, {@code jfr summary}) or JDK Mission Control. Needs the
 * {@code jdk.jfr} module: tools/make-jre.ps1 includes it, but a runtime built before that does
 * not, and says so here instead of failing.
 */
public class FlightRecorder {
    static {
        Console.setscmd("jfr", (cons, args) -> command(cons.out, args));
    }

    /** Makes sure the console command exists. */
    public static void init() {}

    private static void command(PrintWriter out, String[] args) {
        String sub = (args.length > 1) ? args[1] : "";
        try {
            switch (sub) {
                case "start":
                    out.print(Jfr.start((args.length > 2) ? Integer.parseInt(args[2]) : 0) + "\n");
                    break;
                case "stop":
                    out.print(Jfr.stop() + "\n");
                    break;
                default:
                    out.print(Jfr.status() + "\n");
            }
        } catch (NoClassDefFoundError e) {
            out.print("jfr: this Java runtime has no flight recorder (the jdk.jfr module). " +
                      "Rebuild it with tools/make-jre.ps1, or run the client on a full JDK.\n");
        } catch (Exception e) {
            out.print("jfr: " + e + "\n");
        }
        out.flush();
    }

    static Path file() throws java.io.IOException {
        Path dir = Paths.get("logs");
        Files.createDirectories(dir);
        return dir.resolve("jfr-" + new SimpleDateFormat("yyyyMMdd-HHmmss").format(new Date()) + ".jfr").toAbsolutePath();
    }

    /** Everything that names jdk.jfr, so that loading FlightRecorder never needs the module. */
    private static final class Jfr {
        private static jdk.jfr.Recording cur = null;
        private static Path dest = null;

        static synchronized String start(int seconds) throws Exception {
            if ((cur != null) && (cur.getState() == jdk.jfr.RecordingState.RUNNING))
                return "jfr: already recording (to " + dest + ")";
            jdk.jfr.Recording r = new jdk.jfr.Recording(jdk.jfr.Configuration.getConfiguration("profile"));
            r.setName("novocaine");
            r.setToDisk(true);
            dest = file();
            r.setDestination(dest);   /* written here when it stops, by itself or by :jfr stop */
            if (seconds > 0)
                r.setDuration(java.time.Duration.ofSeconds(seconds));
            r.start();
            cur = r;
            return "jfr: recording" + ((seconds > 0) ? " for " + seconds + "s" : " until :jfr stop") + ", to " + dest;
        }

        static synchronized String stop() {
            if (cur == null)
                return "jfr: not recording";
            jdk.jfr.Recording r = cur;
            cur = null;
            if (r.getState() == jdk.jfr.RecordingState.RUNNING)
                r.stop();
            r.close();
            return "jfr: written to " + dest;
        }

        static synchronized String status() {
            if (cur == null)
                return "jfr: not recording. :jfr start [seconds], then :jfr stop";
            if (cur.getState() != jdk.jfr.RecordingState.RUNNING)
                return "jfr: finished, written to " + dest;
            return "jfr: recording to " + dest + " since " + cur.getStartTime();
        }
    }
}
