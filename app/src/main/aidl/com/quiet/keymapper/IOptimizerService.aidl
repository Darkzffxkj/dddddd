package com.quiet.keymapper;

// Runs as Shizuku shell user; app-facing requests are validated in the service.
interface IOptimizerService {
    void destroy() = 16777114;
    String[] listRunningProcesses();
    int forceStopUserApp(String packageName);
    int enableOptionalApp(String packageName);
}
