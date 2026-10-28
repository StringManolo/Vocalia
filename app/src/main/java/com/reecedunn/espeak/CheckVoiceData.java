package com.reecedunn.espeak;

import android.content.Context;
import android.util.Log;
import java.io.File;

public final class CheckVoiceData {

    private static final String TAG = "CheckVoiceData";

    private CheckVoiceData() {}

    /** Devuelve filesDir/espeak-data. Su PARENT (filesDir) es lo que SpeechSynthesis.nativeCreate recibe. */
    public static File getDataPath(Context context) {
        File dir = new File(context.getFilesDir(), "espeak-data");
        if (!dir.exists()) dir.mkdirs();
        return dir;
    }

    /** Los datos reales van a filesDir/espeak-ng-data/. */
    public static boolean hasBaseResources(Context context) {
        File dataDir = new File(context.getFilesDir(), "espeak-ng-data");
        boolean phontab = new File(dataDir, "phontab").exists();
        boolean phondata = new File(dataDir, "phondata").exists();
        if (!phontab || !phondata) {
            Log.w(TAG, "Datos base incompletos en " + dataDir.getAbsolutePath()
                    + " (phontab=" + phontab + ", phondata=" + phondata + ")");
        }
        return phontab && phondata;
    }
}
