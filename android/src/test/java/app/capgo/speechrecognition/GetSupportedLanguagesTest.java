package app.capgo.speechrecognition;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.speech.RecognizerIntent;
import androidx.appcompat.app.AppCompatActivity;
import com.getcapacitor.Bridge;
import com.getcapacitor.JSObject;
import com.getcapacitor.PluginCall;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.json.JSONArray;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowLooper;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = Build.VERSION_CODES.TIRAMISU)
public class GetSupportedLanguagesTest {

    private AppCompatActivity activity;
    private Bridge bridge;
    private SpeechRecognitionPlugin plugin;
    private final AtomicReference<BroadcastReceiver> firstBroadcastReceiver = new AtomicReference<>();
    private final AtomicReference<BroadcastReceiver> secondBroadcastReceiver = new AtomicReference<>();
    private int broadcastInvocation;

    @Before
    public void setUp() throws Exception {
        broadcastInvocation = 0;
        firstBroadcastReceiver.set(null);
        secondBroadcastReceiver.set(null);

        Context appContext = RuntimeEnvironment.getApplication();
        activity = mock(AppCompatActivity.class);
        when(activity.getApplicationContext()).thenReturn(appContext);

        bridge = mock(Bridge.class);
        when(bridge.getActivity()).thenReturn(activity);
        when(bridge.getContext()).thenReturn(appContext);

        doAnswer((invocation) -> {
            BroadcastReceiver receiver = invocation.getArgument(2);
            if (broadcastInvocation == 0) {
                firstBroadcastReceiver.set(receiver);
            } else if (broadcastInvocation == 1) {
                secondBroadcastReceiver.set(receiver);
            }
            broadcastInvocation++;
            return null;
        })
            .when(activity)
            .sendOrderedBroadcast(
                any(Intent.class),
                isNull(),
                any(BroadcastReceiver.class),
                isNull(),
                eq(Activity.RESULT_OK),
                isNull(),
                isNull()
            );

        plugin = new SpeechRecognitionPlugin();
        setBridge(plugin, bridge);
    }

    @Test
    public void getSupportedLanguages_usesFreshReceiverForEachAsyncCall() {
        PluginCall firstCall = mock(PluginCall.class);
        PluginCall secondCall = mock(PluginCall.class);

        plugin.getSupportedLanguages(firstCall);
        plugin.getSupportedLanguages(secondCall);

        assertNotSame(firstBroadcastReceiver.get(), secondBroadcastReceiver.get());
    }

    @Test
    public void getSupportedLanguages_secondCallSettlesAfterFirstReject() {
        PluginCall firstCall = mock(PluginCall.class);
        PluginCall secondCall = mock(PluginCall.class);

        AtomicInteger firstRejectCount = new AtomicInteger(0);
        AtomicInteger secondResolveCount = new AtomicInteger(0);

        doAnswer((invocation) -> {
            firstRejectCount.incrementAndGet();
            return null;
        })
            .when(firstCall)
            .reject(eq("Could not get list of languages"));

        doAnswer((invocation) -> {
            secondResolveCount.incrementAndGet();
            return null;
        })
            .when(secondCall)
            .resolve(any(JSObject.class));

        plugin.getSupportedLanguages(firstCall);
        deliverLanguageBroadcast(firstBroadcastReceiver.get(), null);

        plugin.getSupportedLanguages(secondCall);
        deliverLanguageBroadcast(secondBroadcastReceiver.get(), Arrays.asList("en-US", "fr-FR"));

        assertEquals(1, firstRejectCount.get());
        assertEquals(1, secondResolveCount.get());
        verify(firstCall, times(0)).resolve(any(JSObject.class));
        verify(secondCall, times(0)).reject(any(String.class));

        PluginCall thirdCall = mock(PluginCall.class);
        AtomicReference<JSObject> thirdResult = new AtomicReference<>();
        doAnswer((invocation) -> {
            thirdResult.set(invocation.getArgument(0));
            return null;
        })
            .when(thirdCall)
            .resolve(any(JSObject.class));

        plugin.getSupportedLanguages(thirdCall);

        assertEquals(2, broadcastInvocation);
        assertNotNull(thirdResult.get());
        JSONArray languages = thirdResult.get().getJSONArray("languages");
        assertEquals(2, languages.length());
        assertEquals("en-US", languages.getString(0));
        assertEquals("fr-FR", languages.getString(1));
    }

    @Test
    public void getSupportedLanguages_rejectsWhenActivityMissing() {
        when(bridge.getActivity()).thenReturn(null);
        PluginCall call = mock(PluginCall.class);
        AtomicInteger rejectCount = new AtomicInteger(0);
        doAnswer((invocation) -> {
            rejectCount.incrementAndGet();
            return null;
        })
            .when(call)
            .reject(eq("Could not get list of languages"));

        plugin.getSupportedLanguages(call);

        assertEquals(1, rejectCount.get());
        assertTrue(broadcastInvocation == 0);
    }

    private static void deliverLanguageBroadcast(BroadcastReceiver receiver, List<String> languages) {
        Bundle extras = new Bundle();
        if (languages != null) {
            extras.putStringArrayList(RecognizerIntent.EXTRA_SUPPORTED_LANGUAGES, new ArrayList<>(languages));
        }
        RuntimeEnvironment.getApplication().sendOrderedBroadcast(
            new Intent(RecognizerIntent.ACTION_GET_LANGUAGE_DETAILS),
            null,
            receiver,
            null,
            Activity.RESULT_OK,
            null,
            extras
        );
        ShadowLooper.idleMainLooper();
    }

    private static void setBridge(SpeechRecognitionPlugin plugin, Bridge bridge) throws Exception {
        Field bridgeField = com.getcapacitor.Plugin.class.getDeclaredField("bridge");
        bridgeField.setAccessible(true);
        bridgeField.set(plugin, bridge);
    }
}
