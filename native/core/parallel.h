// Minimal fork-join helper.
#pragma once

#include <algorithm>
#include <thread>
#include <vector>

namespace fsv {

inline unsigned workerCount() { return std::max(1u, std::min(std::thread::hardware_concurrency(), 8u)); }

// Calls fn(begin, end, threadIndex) over [0, n) split into contiguous ranges.
template <typename F>
void parallelFor(size_t n, size_t minPerThread, F&& fn) {
    unsigned threads = workerCount();
    if (minPerThread == 0) minPerThread = 1;
    threads = (unsigned)std::max<size_t>(1, std::min<size_t>(threads, n / minPerThread));
    if (threads <= 1) {
        fn((size_t)0, n, 0u);
        return;
    }
    const size_t range = (n + threads - 1) / threads;
    std::vector<std::thread> pool;
    pool.reserve(threads - 1);
    for (unsigned t = 1; t < threads; ++t) {
        size_t b = t * range, e = std::min(n, b + range);
        if (b < e) pool.emplace_back([&fn, b, e, t]() { fn(b, e, t); });
    }
    fn((size_t)0, std::min(n, range), 0u);
    for (auto& th : pool) th.join();
}

}  // namespace fsv
