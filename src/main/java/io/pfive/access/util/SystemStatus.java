// Copyright 2023-2025 Phase Five LLC.  For license terms, see LICENSE.txt in the repository root.

package io.pfive.access.util;

import com.sun.management.OperatingSystemMXBean;

import java.lang.management.ManagementFactory;

/// Collect information from the Java runtime and operating system, which can be used internally
/// by the backend or serialized and sent to clients.
public class SystemStatus {

    /// The maximum amount of memory the JVM will attempt to allocate, in bytes.
    /// This is a launch-time configuration option and should not change over the life of a JVM instance.
    public final long jvmMaxMemory;

    /// The amount of memory the JVM has currently allocated in bytes, including unused spare capacity.
    /// This is expected to change over the life of a single JVM instance, as a sort of high-water
    /// mark for memory use. On newer JVMs it can both rise and fall over time.
    public final long jvmCurrentMemory;

    /// The amount of memory within the JVM's allocated space that is available for new objects.
    /// This is expected to continually rise and fall as garbage collection occurs.
    public final long jvmFreeMemory;

    /// The amount of memory within the JVM's allocated space that currently contains objects.
    /// This is expected to continually rise and fall as garbage collection occurs.
    /// Used memory and free memory will always add up to current memory.
    public final long jvmUsedMemory;

    /// The total amount of memory in the machine in bytes, as reported by the OS.
    /// Note that the JVM is typically constrained to use less than this, see jvmMaxMemory.
    public final long osTotalMemory;

    /// The total amount of memory available for use in bytes, as reported by the OS.
    /// Note that the JVM will typically not be able to use all of this, see jvmMaxMemory.
    public final long osFreeMemory;

    public final long osUsedMemory;

    /// The number of processor cores in the machine as reported by the OS.
    /// May include "hyperthreading" pseudo-cores, so may be double the actual number of cores.
    public final int totalCpuCores;

    /// The number of processor cores in the machine that are occupied, as implied by the load.
    public final double usedCpuCores;

    /// CPU load in the range 0-1, attributed to the current JVM only.
    public final double jvmLoad;

    /// CPU load in the range 0-1, attributed to all processes including the current JVM.
    public final double osLoad;

    /// The architecture of the machine as reported by the OS.
    public final String architecture;

    /// It is not possible from Java to tell whether some cores are fake "hyperthreading" cores.
    /// Typically there are two per actual core. On Linux systems it may be possible to guess this
    /// from /proc/cpuinfo contents.
    private static final int FAKE_CORE_DIVISOR = 2;

    public SystemStatus () {
        // Data sources are the JVM Runtime object and the OS "Management Bean".
        Runtime jvm = Runtime.getRuntime();
        OperatingSystemMXBean os = ManagementFactory.getPlatformMXBean(OperatingSystemMXBean.class);

        jvmMaxMemory = jvm.maxMemory();
        jvmCurrentMemory = jvm.totalMemory();
        jvmFreeMemory = jvm.freeMemory();
        jvmUsedMemory = jvmCurrentMemory - jvmFreeMemory;

        osTotalMemory = os.getTotalMemorySize();
        osFreeMemory = os.getFreeMemorySize();
        osUsedMemory = osTotalMemory - osFreeMemory;

        jvmLoad = os.getProcessCpuLoad();
        osLoad = os.getCpuLoad();

        totalCpuCores = jvm.availableProcessors() / FAKE_CORE_DIVISOR;
        usedCpuCores = totalCpuCores * osLoad;

        architecture = os.getArch();
    }

}
