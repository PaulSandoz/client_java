package io.prometheus.client.hotspot;

import com.sun.management.UnixOperatingSystemMXBean;
import io.prometheus.client.Collector;
import io.prometheus.client.CounterMetricFamily;
import io.prometheus.client.GaugeMetricFamily;

import java.io.BufferedReader;
import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.lang.management.OperatingSystemMXBean;
import java.lang.management.RuntimeMXBean;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Exports the standard exports common across all prometheus clients.
 * <p>
 * This includes stats like CPU time spent and memory usage.
 * <p>
 * Example usage:
 * <pre>
 * {@code
 *   new StandardExports().register();
 * }
 * </pre>
 */
public class StandardExports extends Collector {
  private static final Logger LOGGER = Logger.getLogger(StandardExports.class.getName());

  private final StatusReader statusReader;
  private final OperatingSystemMXBean osBean;
  private final RuntimeMXBean runtimeBean;
  private final boolean linux;

  public StandardExports() {
    this(new StatusReader(),
         ManagementFactory.getOperatingSystemMXBean(),
         ManagementFactory.getRuntimeMXBean());
  }

  StandardExports(StatusReader statusReader, OperatingSystemMXBean osBean, RuntimeMXBean runtimeBean) {
      this.statusReader = statusReader;
      this.osBean = osBean;
      this.runtimeBean = runtimeBean;
      this.linux = (osBean.getName().indexOf("Linux") == 0);
  }

  private final static double KB = 1024;

  public List<MetricFamilySamples> collect() {
    List<MetricFamilySamples> mfs = new ArrayList<MetricFamilySamples>();

    mfs.add(new GaugeMetricFamily("process_start_time_seconds", "Start time of the process since unix epoch in seconds.",
                                  runtimeBean.getStartTime() / MILLISECONDS_PER_SECOND));

    if (osBean instanceof com.sun.management.OperatingSystemMXBean) {
      com.sun.management.OperatingSystemMXBean b = (com.sun.management.OperatingSystemMXBean) osBean;
      mfs.add(new CounterMetricFamily("process_cpu_seconds_total", "Total user and system CPU time spent in seconds.",
                                      b.getProcessCpuTime() / NANOSECONDS_PER_SECOND));
    } else {
      try {
        /*
        Support com.ibm.lang.management.OperatingSystemMXBean, which is independent
        of com.sun.management.OperatingSystemMXBean.  Use reflective access to
        avoid a compile-time dependency.  Note that the method should be retrieved
        from the class of com.ibm.lang.management.OperatingSystemMXBean rather than
        any implementing class (osBean.getClass()), otherwise on Java 9 access may
        be denied.
        */
        Class<?> ibmBeanClass = Class.forName("com.ibm.lang.management.OperatingSystemMXBean", true,
                                              osBean.getClass().getClassLoader());
        Method method = ibmBeanClass.getMethod("getProcessCpuTime", null);
        Long l = (Long) method.invoke(osBean);
        mfs.add(new CounterMetricFamily("process_cpu_seconds_total", "Total user and system CPU time spent in seconds.",
                                        l / NANOSECONDS_PER_SECOND));
      }
      catch (Exception e) {
        LOGGER.log(Level.FINE,"Could not access process cpu time", e);
      }
    }

    if (osBean instanceof UnixOperatingSystemMXBean) {
      UnixOperatingSystemMXBean unixBean = (UnixOperatingSystemMXBean) osBean;
      mfs.add(new GaugeMetricFamily("process_open_fds", "Number of open file descriptors.",
          unixBean.getOpenFileDescriptorCount()));
      mfs.add(new GaugeMetricFamily("process_max_fds", "Maximum number of open file descriptors.",
          unixBean.getMaxFileDescriptorCount()));
    }

    // There's no standard Java or POSIX way to get memory stats,
    // so add support for just Linux for now.
    if (linux) {
      try {
        collectMemoryMetricsLinux(mfs);
      } catch (Exception e) {
        // If the format changes, log a warning and return what we can.
        LOGGER.warning(e.toString());
      }
    }
    return mfs;
  }

  void collectMemoryMetricsLinux(List<MetricFamilySamples> mfs) {
    // statm/stat report in pages, and it's non-trivial to get pagesize from Java
    // so we parse status instead.
    BufferedReader br = null;
    try {
      br = statusReader.procSelfStatusReader();
      String line;
      while ((line = br.readLine()) != null) {
        if (line.startsWith("VmSize:")) {
          mfs.add(new GaugeMetricFamily("process_virtual_memory_bytes",
              "Virtual memory size in bytes.",
              Float.parseFloat(line.split("\\s+")[1]) * KB));
        } else if (line.startsWith("VmRSS:")) {
          mfs.add(new GaugeMetricFamily("process_resident_memory_bytes",
              "Resident memory size in bytes.",
              Float.parseFloat(line.split("\\s+")[1]) * KB));
        }
      }
    } catch (IOException e) {
      LOGGER.fine(e.toString());
    } finally {
      if (br != null) {
        try {
          br.close();
        } catch (IOException e) {
          LOGGER.fine(e.toString());
        }
      }
    }
  }

  static class StatusReader {
    BufferedReader procSelfStatusReader() throws FileNotFoundException {
      return new BufferedReader(new FileReader("/proc/self/status"));
    }
  }
}
