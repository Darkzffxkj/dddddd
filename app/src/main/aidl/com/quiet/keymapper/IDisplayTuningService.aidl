package com.quiet.keymapper;

interface IDisplayTuningService {
    void destroy() = 16777114;
    String getDisplayInfo() = 1;
    int applyTemporary(int width, int height, int dpi) = 2;
    int confirmTemporary() = 3;
    int restoreDefault() = 4;
}
