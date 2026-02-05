import './style.css';
import { SpeechRecognition } from '@capgo/capacitor-speech-recognition';

const refs = {
  availability: document.getElementById('availability'),
  permission: document.getElementById('permission'),
  listening: document.getElementById('listening'),
  eventLog: document.getElementById('event-log'),
  language: document.getElementById('language'),
  maxResults: document.getElementById('max-results'),
  prompt: document.getElementById('prompt'),
  allowSilence: document.getElementById('allow-silence'),
  partialResults: document.getElementById('partial-results'),
  popup: document.getElementById('popup'),
  addPunctuation: document.getElementById('add-punctuation'),
  checkAvailability: document.getElementById('check-availability'),
  checkPermission: document.getElementById('check-permission'),
  requestPermission: document.getElementById('request-permission'),
  startListening: document.getElementById('start-listening'),
  stopListening: document.getElementById('stop-listening'),
  // PTT refs
  pttMode: document.getElementById('ptt-mode'),
  continuousPTT: document.getElementById('continuous-ptt'),
  pttControls: document.getElementById('ptt-controls'),
  pttButton: document.getElementById('ptt-button'),
  pttState: document.getElementById('ptt-state'),
  pttStatusContainer: document.getElementById('ptt-status-container'),
  accumulatedResults: document.getElementById('accumulated-results'),
  accumulatedText: document.getElementById('accumulated-text'),
};

let partialListener;
let listeningListener;
let segmentListener;
let segmentedSessionListener;

// PTT mode state
let pttModeEnabled = false;
let isPTTHeld = false;

const formatTime = () => new Date().toLocaleTimeString();

function appendEvent(label, detail) {
  const entry = document.createElement('div');
  entry.className = 'event-log-entry';
  const time = document.createElement('time');
  time.textContent = formatTime();
  const body = document.createElement('div');
  body.textContent = detail ? `${label}: ${detail}` : label;
  entry.appendChild(time);
  entry.appendChild(body);
  if (refs.eventLog.firstChild?.classList?.contains('muted')) {
    refs.eventLog.innerHTML = '';
  }
  refs.eventLog.prepend(entry);
  while (refs.eventLog.childElementCount > 40) {
    refs.eventLog.removeChild(refs.eventLog.lastElementChild);
  }
}

async function updateAvailability() {
  try {
    const { available } = await SpeechRecognition.available();
    refs.availability.textContent = available ? 'yes' : 'no';
    appendEvent('available()', available ? 'service ready' : 'not available');
  } catch (error) {
    appendEvent('available() error', error?.message ?? String(error));
  }
}

async function updatePermissions() {
  try {
    const { speechRecognition } = await SpeechRecognition.checkPermissions();
    refs.permission.textContent = speechRecognition;
    appendEvent('checkPermissions()', speechRecognition);
  } catch (error) {
    appendEvent('checkPermissions() error', error?.message ?? String(error));
  }
}

async function requestPermissions() {
  try {
    const { speechRecognition } = await SpeechRecognition.requestPermissions();
    refs.permission.textContent = speechRecognition;
    appendEvent('requestPermissions()', speechRecognition);
  } catch (error) {
    appendEvent('requestPermissions() error', error?.message ?? String(error));
  }
}

function syncListeningState(status) {
  const active = status === 'started';
  refs.listening.textContent = active ? 'yes' : 'no';
}

function togglePTTMode(enabled) {
  pttModeEnabled = enabled;
  refs.pttControls.classList.toggle('hidden', !enabled);
  refs.startListening.parentElement.classList.toggle('hidden', enabled);
  refs.pttStatusContainer.classList.toggle('hidden', !enabled);
  refs.continuousPTT.disabled = !enabled;
  if (!enabled) refs.continuousPTT.checked = false;
  appendEvent('PTT Mode', enabled ? 'enabled' : 'disabled');
}

async function ensureListeners() {
  if (!partialListener) {
    partialListener = await SpeechRecognition.addListener('partialResults', (event) => {
      const matches = event.matches?.join(' | ') ?? '(empty)';
      if (event.accumulated) {
        refs.accumulatedText.textContent = event.accumulated;
        refs.accumulatedResults.classList.remove('hidden');
        const detail = event.isRestarting ? `${matches} [restarting...]` : matches;
        appendEvent('partialResults', `${detail} (accumulated: ${event.accumulated})`);
      } else {
        appendEvent('partialResults', matches);
      }
    });
  }

  if (!listeningListener) {
    listeningListener = await SpeechRecognition.addListener('listeningState', (event) => {
      syncListeningState(event.status);
      appendEvent('listeningState', event.status);
    });
  }

  if (!segmentListener) {
    segmentListener = await SpeechRecognition.addListener('segmentResults', (event) => {
      const matches = event.matches?.join(' | ') ?? '(empty)';
      appendEvent('segmentResults', matches);
    });
  }

  if (!segmentedSessionListener) {
    segmentedSessionListener = await SpeechRecognition.addListener('endOfSegmentedSession', () => {
      appendEvent('endOfSegmentedSession', 'Segment session ended');
    });
  }
}

function parseOptions() {
  const language = refs.language.value.trim() || 'en-US';
  const maxResults = Math.max(1, Math.min(Number(refs.maxResults.value) || 5, 5));
  const allowForSilence = Math.max(0, Number(refs.allowSilence.value) || 0);
  const options = {
    language,
    maxResults,
    prompt: refs.prompt.value || undefined,
    partialResults: refs.partialResults.checked,
    popup: refs.popup.checked,
    addPunctuation: refs.addPunctuation.checked,
    allowForSilence,
    continuousPTT: pttModeEnabled && refs.continuousPTT.checked,
  };
  return options;
}

async function startListening() {
  try {
    await ensureListeners();
    const options = parseOptions();
    appendEvent('start()', JSON.stringify(options));
    const result = await SpeechRecognition.start(options);
    if (!options.partialResults && result?.matches) {
      appendEvent('final matches', result.matches.join(' | '));
    } else if (!options.partialResults) {
      appendEvent('final matches', 'No matches returned.');
    }
  } catch (error) {
    appendEvent('start() error', error?.message ?? String(error));
  }
}

async function stopListening() {
  try {
    await SpeechRecognition.stop();
    appendEvent('stop()', 'requested');
  } catch (error) {
    appendEvent('stop() error', error?.message ?? String(error));
  }
}

async function handlePTTPress() {
  if (isPTTHeld) return;
  isPTTHeld = true;
  refs.pttButton.classList.add('active');
  refs.pttState.textContent = 'held';
  refs.accumulatedResults.classList.add('hidden');
  refs.accumulatedText.textContent = '';

  try {
    await SpeechRecognition.setPTTState({ held: true });
    appendEvent('setPTTState()', 'held: true');
    await ensureListeners();
    const options = parseOptions();
    appendEvent('start() [PTT]', JSON.stringify(options));
    await SpeechRecognition.start(options);
  } catch (error) {
    appendEvent('PTT start error', error?.message ?? String(error));
    handlePTTRelease();
  }
}

async function handlePTTRelease() {
  if (!isPTTHeld) return;
  isPTTHeld = false;
  refs.pttButton.classList.remove('active');
  refs.pttState.textContent = 'released';

  try {
    await SpeechRecognition.setPTTState({ held: false });
    appendEvent('setPTTState()', 'held: false');
    appendEvent('forceStop()', 'requesting...');
    await SpeechRecognition.forceStop({ timeout: 1500 });
    appendEvent('forceStop()', 'completed');
    const lastResult = await SpeechRecognition.getLastPartialResult();
    if (lastResult.available) {
      appendEvent('getLastPartialResult()', lastResult.text);
    }
  } catch (error) {
    appendEvent('PTT stop error', error?.message ?? String(error));
  }
}

async function bootstrap() {
  refs.checkAvailability.addEventListener('click', updateAvailability);
  refs.checkPermission.addEventListener('click', updatePermissions);
  refs.requestPermission.addEventListener('click', requestPermissions);
  refs.startListening.addEventListener('click', startListening);
  refs.stopListening.addEventListener('click', stopListening);

  // PTT mode toggle
  refs.pttMode.addEventListener('change', (e) => togglePTTMode(e.target.checked));

  // PTT button - mouse events
  refs.pttButton.addEventListener('mousedown', handlePTTPress);
  refs.pttButton.addEventListener('mouseup', handlePTTRelease);
  refs.pttButton.addEventListener('mouseleave', handlePTTRelease);

  // PTT button - touch events
  refs.pttButton.addEventListener('touchstart', (e) => {
    e.preventDefault();
    handlePTTPress();
  });
  refs.pttButton.addEventListener('touchend', (e) => {
    e.preventDefault();
    handlePTTRelease();
  });
  refs.pttButton.addEventListener('touchcancel', handlePTTRelease);

  await Promise.all([updateAvailability(), updatePermissions()]);
}

bootstrap();

window.addEventListener('beforeunload', () => {
  partialListener?.remove();
  listeningListener?.remove();
  segmentListener?.remove();
  segmentedSessionListener?.remove();
});
