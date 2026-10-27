package app.capgo.speechrecognition;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.speech.RecognizerIntent;
import com.getcapacitor.JSArray;
import com.getcapacitor.JSObject;
import com.getcapacitor.PluginCall;
import java.util.List;
import java.util.function.Consumer;

public class Receiver extends BroadcastReceiver implements Constants {

    private List<String> supportedLanguagesList;
    private String languagePref;
    private final PluginCall call;
    private final Consumer<List<String>> onLanguagesResolved;

    public Receiver(PluginCall call) {
        this(call, null);
    }

    public Receiver(PluginCall call, Consumer<List<String>> onLanguagesResolved) {
        super();
        this.call = call;
        this.onLanguagesResolved = onLanguagesResolved;
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        Bundle extras = getResultExtras(true);

        if (extras.containsKey(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE)) {
            languagePref = extras.getString(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE);
        }

        if (extras.containsKey(RecognizerIntent.EXTRA_SUPPORTED_LANGUAGES)) {
            supportedLanguagesList = extras.getStringArrayList(RecognizerIntent.EXTRA_SUPPORTED_LANGUAGES);
            if (onLanguagesResolved != null) {
                onLanguagesResolved.accept(supportedLanguagesList);
            }

            JSArray languagesList = new JSArray(supportedLanguagesList);
            call.resolve(new JSObject().put("languages", languagesList));
            return;
        }

        call.reject(LANGUAGE_ERROR);
    }

    public List<String> getSupportedLanguages() {
        return supportedLanguagesList;
    }

    public String getLanguagePreference() {
        return languagePref;
    }
}
