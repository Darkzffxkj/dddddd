package com.quiet.keymapper;
interface IOptimizerService {
    boolean isRunning(String packageName);
    int forceStop(String packageName);
}