package app.capgo.speechrecognition;

import android.Manifest;

public interface Constants {
    int REQUEST_CODE_PERMISSION = 2001;
    int REQUEST_CODE_SPEECH = 2002;
    int MAX_RESULTS = 5;
    String NOT_AVAILABLE = "Speech recognition service is not available.";
    String MISSING_PERMISSION = "Missing permission";
    String SEGMENT_RESULTS_EVENT = "segmentResults";
    String END_OF_SEGMENT_EVENT = "endOfSegmentedSession";
    String LISTENING_EVENT = "listeningState";
    String PARTIAL_RESULTS_EVENT = "partialResults";
    String ERROR_EVENT = "error";
    String READY_FOR_NEXT_SESSION_EVENT = "readyForNextSession";
    String RECORD_AUDIO_PERMISSION = Manifest.permission.RECORD_AUDIO;
    String LANGUAGE_ERROR = "Could not get list of languages";
}
