package com.quiet.keymapper;

// Runs as Shizuku shell user; app-facing requests are validated in the service.
interface IOptimizerService {
    void destroy() = 16777114;
    String[] listRunningProcesses() = 1;
    int forceStopUserApp(String packageName) = 2;
    int enableOptionalApp(String packageName) = 3;
}
