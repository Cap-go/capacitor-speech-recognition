package app.capgo.speechrecognition;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.speech.RecognitionSupportCallback;
import android.speech.SpeechRecognizer;
import androidx.appcompat.app.AppCompatActivity;
import com.getcapacitor.Bridge;
import com.getcapacitor.JSObject;
import com.getcapacitor.PluginCall;
import java.lang.reflect.Field;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;

@RunWith(RobolectricTestRunner.class)
@Config(
    sdk = Build.VERSION_CODES.UPSIDE_DOWN_CAKE,
    shadows = SpeechRecognitionPluginMainThreadTest.ShadowSpeechRecognizerForThreadCheck.class
)
public class SpeechRecognitionPluginMainThreadTest {

    private HandlerThread pluginThread;
    private Handler pluginHandler;

    @Before
    public void setUp() {
        ShadowSpeechRecognizerForThreadCheck.reset();
        pluginThread = new HandlerThread("CapacitorPlugins");
        pluginThread.start();
        pluginHandler = new Handler(pluginThread.getLooper());
    }

    @After
    public void tearDown() {
        pluginThread.quitSafely();
    }

    @Test
    public void isOnDeviceRecognitionAvailable_createsRecognizerOnMainThread() throws Exception {
        AppCompatActivity activity = mock(AppCompatActivity.class);
        Context appContext = RuntimeEnvironment.getApplication();
        when(activity.getApplicationContext()).thenReturn(appContext);
        when(activity.getPackageName()).thenReturn("app.capgo.speechrecognition.test");
        doAnswer((invocation) -> {
            Runnable runnable = invocation.getArgument(0);
            runnable.run();
            return null;
        })
            .when(activity)
            .runOnUiThread(any());
        Bridge bridge = mock(Bridge.class);
        when(bridge.getActivity()).thenReturn(activity);
        when(bridge.getContext()).thenReturn(appContext);
        doAnswer((invocation) -> {
            Runnable runnable = invocation.getArgument(0);
            new Handler(Looper.getMainLooper()).post(runnable);
            return null;
        })
            .when(bridge)
            .executeOnMainThread(any());

        SpeechRecognitionPlugin plugin = new SpeechRecognitionPlugin();
        setBridge(plugin, bridge);

        PluginCall call = mock(PluginCall.class);
        when(call.getString(eq("language"), any())).thenReturn("de-DE");

        AtomicInteger resolveCount = new AtomicInteger(0);
        doAnswer((invocation) -> {
            resolveCount.incrementAndGet();
            return null;
        })
            .when(call)
            .resolve(any(JSObject.class));

        CountDownLatch invoked = new CountDownLatch(1);
        pluginHandler.post(() -> {
            plugin.isOnDeviceRecognitionAvailable(call);
            invoked.countDown();
        });
        assertTrue(invoked.await(5, TimeUnit.SECONDS));

        org.robolectric.shadows.ShadowLooper.runUiThreadTasksIncludingDelayedTasks();

        assertTrue(
            "SpeechRecognizer.createOnDeviceSpeechRecognizer must run on the main thread",
            ShadowSpeechRecognizerForThreadCheck.createdOnMainThread.get()
        );
        assertEquals("Plugin call should resolve exactly once", 1, resolveCount.get());
    }

    @Test
    public void isOnDeviceRecognitionAvailable_resolvesWhenCheckRecognitionSupportThrows() throws Exception {
        ShadowSpeechRecognizerForThreadCheck.throwOnCheckRecognitionSupport.set(true);

        AppCompatActivity activity = mock(AppCompatActivity.class);
        Context appContext = RuntimeEnvironment.getApplication();
        when(activity.getApplicationContext()).thenReturn(appContext);
        when(activity.getPackageName()).thenReturn("app.capgo.speechrecognition.test");
        doAnswer((invocation) -> {
            Runnable runnable = invocation.getArgument(0);
            runnable.run();
            return null;
        })
            .when(activity)
            .runOnUiThread(any());
        Bridge bridge = mock(Bridge.class);
        when(bridge.getActivity()).thenReturn(activity);
        when(bridge.getContext()).thenReturn(appContext);
        doAnswer((invocation) -> {
            Runnable runnable = invocation.getArgument(0);
            new Handler(Looper.getMainLooper()).post(runnable);
            return null;
        })
            .when(bridge)
            .executeOnMainThread(any());

        SpeechRecognitionPlugin plugin = new SpeechRecognitionPlugin();
        setBridge(plugin, bridge);

        PluginCall call = mock(PluginCall.class);
        when(call.getString(eq("language"), any())).thenReturn("de-DE");

        AtomicInteger resolveCount = new AtomicInteger(0);
        AtomicBoolean resolvedAvailable = new AtomicBoolean(true);
        doAnswer((invocation) -> {
            resolveCount.incrementAndGet();
            JSObject result = invocation.getArgument(0);
            resolvedAvailable.set(result.getBoolean("available"));
            return null;
        })
            .when(call)
            .resolve(any(JSObject.class));

        CountDownLatch invoked = new CountDownLatch(1);
        pluginHandler.post(() -> {
            plugin.isOnDeviceRecognitionAvailable(call);
            invoked.countDown();
        });
        assertTrue(invoked.await(5, TimeUnit.SECONDS));

        org.robolectric.shadows.ShadowLooper.runUiThreadTasksIncludingDelayedTasks();

        assertEquals("Plugin call should resolve exactly once", 1, resolveCount.get());
        assertEquals("Availability should be false when support check throws", false, resolvedAvailable.get());
        assertEquals("Temporary support checker should be destroyed", 1, ShadowSpeechRecognizerForThreadCheck.destroyCount.get());
    }

    @Test
    public void isOnDeviceRecognitionAvailable_resolvesFalseWhenCreateThrowsUnsupportedOperation() throws Exception {
        ShadowSpeechRecognizerForThreadCheck.throwOnCreate.set(true);

        AppCompatActivity activity = mock(AppCompatActivity.class);
        Context appContext = RuntimeEnvironment.getApplication();
        when(activity.getApplicationContext()).thenReturn(appContext);
        when(activity.getPackageName()).thenReturn("app.capgo.speechrecognition.test");
        doAnswer((invocation) -> {
            Runnable runnable = invocation.getArgument(0);
            runnable.run();
            return null;
        })
            .when(activity)
            .runOnUiThread(any());
        Bridge bridge = mock(Bridge.class);
        when(bridge.getActivity()).thenReturn(activity);
        when(bridge.getContext()).thenReturn(appContext);
        doAnswer((invocation) -> {
            Runnable runnable = invocation.getArgument(0);
            new Handler(Looper.getMainLooper()).post(runnable);
            return null;
        })
            .when(bridge)
            .executeOnMainThread(any());

        SpeechRecognitionPlugin plugin = new SpeechRecognitionPlugin();
        setBridge(plugin, bridge);

        PluginCall call = mock(PluginCall.class);
        when(call.getString(eq("language"), any())).thenReturn("de-DE");

        AtomicInteger resolveCount = new AtomicInteger(0);
        AtomicBoolean resolvedAvailable = new AtomicBoolean(true);
        doAnswer((invocation) -> {
            resolveCount.incrementAndGet();
            JSObject result = invocation.getArgument(0);
            resolvedAvailable.set(result.getBoolean("available"));
            return null;
        })
            .when(call)
            .resolve(any(JSObject.class));

        CountDownLatch invoked = new CountDownLatch(1);
        pluginHandler.post(() -> {
            plugin.isOnDeviceRecognitionAvailable(call);
            invoked.countDown();
        });
        assertTrue(invoked.await(5, TimeUnit.SECONDS));

        org.robolectric.shadows.ShadowLooper.runUiThreadTasksIncludingDelayedTasks();

        assertEquals("Plugin call should resolve exactly once", 1, resolveCount.get());
        assertEquals("Availability should be false when create throws", false, resolvedAvailable.get());
        assertEquals("No recognizer instance to destroy when create throws", 0, ShadowSpeechRecognizerForThreadCheck.destroyCount.get());
    }

    private static void setBridge(SpeechRecognitionPlugin plugin, Bridge bridge) throws Exception {
        Field bridgeField = com.getcapacitor.Plugin.class.getDeclaredField("bridge");
        bridgeField.setAccessible(true);
        bridgeField.set(plugin, bridge);
    }

    @Implements(SpeechRecognizer.class)
    public static class ShadowSpeechRecognizerForThreadCheck {

        static final AtomicBoolean createdOnMainThread = new AtomicBoolean(false);
        static final AtomicBoolean throwOnCheckRecognitionSupport = new AtomicBoolean(false);
        static final AtomicBoolean throwOnCreate = new AtomicBoolean(false);
        static final AtomicInteger destroyCount = new AtomicInteger(0);

        static void reset() {
            createdOnMainThread.set(false);
            throwOnCheckRecognitionSupport.set(false);
            throwOnCreate.set(false);
            destroyCount.set(0);
        }

        @Implementation
        public static boolean isOnDeviceRecognitionAvailable(Context context) {
            return true;
        }

        @Implementation
        public static SpeechRecognizer createOnDeviceSpeechRecognizer(Context context) {
            createdOnMainThread.set(Looper.myLooper() == Looper.getMainLooper());
            if (throwOnCreate.get()) {
                throw new UnsupportedOperationException("on-device recognizer unavailable");
            }
            SpeechRecognizer recognizer = mock(SpeechRecognizer.class);
            doAnswer((invocation) -> {
                if (throwOnCheckRecognitionSupport.get()) {
                    throw new IllegalStateException("synchronous support check failure");
                }
                Executor executor = invocation.getArgument(1);
                RecognitionSupportCallback callback = invocation.getArgument(2);
                executor.execute(() -> {
                    callback.onError(SpeechRecognizer.ERROR_CLIENT);
                    callback.onError(SpeechRecognizer.ERROR_CLIENT);
                });
                return null;
            })
                .when(recognizer)
                .checkRecognitionSupport(any(Intent.class), any(Executor.class), any(RecognitionSupportCallback.class));
            doAnswer((invocation) -> {
                destroyCount.incrementAndGet();
                return null;
            })
                .when(recognizer)
                .destroy();
            return recognizer;
        }
    }
}
