package com.example.demo

import com.sun.management.HotSpotDiagnosticMXBean
import org.springframework.web.bind.annotation.*
import java.lang.management.ManagementFactory
import java.lang.management.MemoryType

@RestController
@RequestMapping("/memory")
class MemoryController {

    // Keeps strong references so GC can't collect them
    private val allocations = mutableListOf<ByteArray>()

    @GetMapping("/stats")
    fun stats(): Map<String, Any> {
        val memMxBeans = ManagementFactory.getMemoryPoolMXBeans()
        val runtime = ManagementFactory.getRuntimeMXBean()
        val threads = ManagementFactory.getThreadMXBean()
        val heap = ManagementFactory.getMemoryMXBean().heapMemoryUsage
        val nonHeap = ManagementFactory.getMemoryMXBean().nonHeapMemoryUsage

        val pools = memMxBeans.associate { pool ->
            pool.name to mapOf(
                "type" to pool.type,
                "used_mb" to pool.usage.used / 1_048_576,
                "committed_mb" to pool.usage.committed / 1_048_576,
                "max_mb" to if (pool.usage.max < 0) "unlimited" else pool.usage.max / 1_048_576,
            )
        }

        return mapOf(
            "heap" to mapOf(
                "used_mb" to heap.used / 1_048_576,
                "committed_mb" to heap.committed / 1_048_576,
                "max_mb" to heap.max / 1_048_576,
            ),
            "non_heap" to mapOf(
                "used_mb" to nonHeap.used / 1_048_576,
                "committed_mb" to nonHeap.committed / 1_048_576,
            ),
            "thread_count" to threads.threadCount,
            "uptime_seconds" to runtime.uptime / 1000,
            "active_allocations_mb" to allocations.sumOf { it.size } / 1_048_576,
            "pools" to pools,
        )
    }

    @PostMapping("/allocate")
    fun allocate(@RequestParam mb: Int = 100): Map<String, Any> {
        val bytes = ByteArray(mb * 1_048_576) { it.toByte() }
        allocations.add(bytes)
        return mapOf(
            "allocated_mb" to mb,
            "total_allocated_mb" to allocations.sumOf { it.size } / 1_048_576,
        )
    }

    @PostMapping("/free")
    fun free(): Map<String, Any> {
        val total = allocations.sumOf { it.size } / 1_048_576
        allocations.clear()
        System.gc()
        return mapOf("freed_mb" to total)
    }

    @PostMapping("/dump")
    fun dump(@RequestParam path: String = "/dumps/heap.hprof"): Map<String, String> {
        val bean = ManagementFactory.newPlatformMXBeanProxy(
            ManagementFactory.getPlatformMBeanServer(),
            "com.sun.management:type=HotSpotDiagnostic",
            HotSpotDiagnosticMXBean::class.java,
        )
        bean.dumpHeap(path, true)
        return mapOf("dumped_to" to path)
    }
}
