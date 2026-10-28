package com.reecedunn.espeak;

import android.content.Context;
import android.util.Log;

import java.io.File;

/**
 * Versión mínima de CheckVoiceData para uso embebido en otra app.
 *
 * A diferencia del original de espeak-ng-android (que lee R.raw.espeakdata
 * y R.raw.espeakdata_version), esta versión trabaja sobre un directorio fijo
 * en filesDir, que es donde Vocalia (EspeakNgEngine.kt) extrae el ZIP.
 */
public final class CheckVoiceData {

    private static final String TAG = "CheckVoiceData";
    private static final String DIR_NAME = "espeak-data";

    private CheckVoiceData() {}

    /** Directorio donde viven phontab, phondata, lang/, etc. */
    public static File getDataPath(Context context) {
        File dir = new File(context.getFilesDir(), DIR_NAME);
        if (!dir.exists()) {
            //noinspection ResultOfMethodCallIgnored
            dir.mkdirs();
        }
        return dir;
    }

    /** Devuelve true si los datos base parecen presentes. */
    public static boolean hasBaseResources(Context context) {
        File dir = getDataPath(context);
        // phontab y phondata son los dos archivos que espeak-ng necesita siempre.
        boolean phontab = new File(dir, "phontab").exists();
        boolean phondata = new File(dir, "phondata").exists();
        if (!phontab || !phondata) {
            Log.w(TAG, "Datos base incompletos en " + dir.getAbsolutePath()
                    + " (phontab=" + phontab + ", phondata=" + phondata + ")");
        }
        return phontab && phondata;
    }
}
