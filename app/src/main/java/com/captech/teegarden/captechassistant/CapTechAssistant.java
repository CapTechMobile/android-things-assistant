package com.captech.teegarden.captechassistant;

import android.app.Activity;
import android.content.Context;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.media.AudioTrack;
import android.media.MediaRecorder;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.support.annotation.Nullable;
import android.util.Log;

import com.google.android.things.pio.Gpio;
import com.google.android.things.pio.PeripheralManagerService;
import com.google.assistant.embedded.v1alpha1.AudioInConfig;
import com.google.assistant.embedded.v1alpha1.AudioOutConfig;
import com.google.assistant.embedded.v1alpha1.ConverseConfig;
import com.google.assistant.embedded.v1alpha1.ConverseRequest;
import com.google.assistant.embedded.v1alpha1.ConverseResponse;
import com.google.assistant.embedded.v1alpha1.ConverseState;
import com.google.assistant.embedded.v1alpha1.EmbeddedAssistantGrpc;
import com.google.protobuf.ByteString;

import org.json.JSONException;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Timer;
import java.util.TimerTask;

import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.auth.MoreCallCredentials;
import io.grpc.stub.StreamObserver;


/**
 * Created by teegarcs on 6/30/17.
 * <p>
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * <p>
 * http://www.apache.org/licenses/LICENSE-2.0
 * <p>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

public class CapTechAssistant extends Activity implements CapTechSphinxManager.SphinxListener {

    private static String TAG = "CapTechAssistant";
    //GPIO Constants
    private static final String BLUE_LIGHT = "BCM6";
    private static final String RED_LIGHT = "BCM26";
    //light status flags
    private boolean blueLightOn, redLightOn;
    private Timer blinkingTimer;

    // Google Assistant API constants.
    private static final String ASSISTANT_ENDPOINT = "embeddedassistant.googleapis.com";

    //don't forget our conversation!
    private ByteString mConversationState = null;

    // Audio constants for Assistant
    private static final int SAMPLE_RATE = 16000;
    private static final int ENCODING = AudioFormat.ENCODING_PCM_16BIT;
    private static AudioInConfig.Encoding ENCODING_INPUT = AudioInConfig.Encoding.LINEAR16;
    private static AudioOutConfig.Encoding ENCODING_OUTPUT = AudioOutConfig.Encoding.LINEAR16;
    private static final int SAMPLE_BLOCK_SIZE = 1024;
    private static int mAudioTrackVolume = 100;

    private static final AudioInConfig ASSISTANT_AUDIO_REQUEST_CONFIG =
            AudioInConfig.newBuilder()
                    .setEncoding(ENCODING_INPUT)
                    .setSampleRateHertz(SAMPLE_RATE)
                    .build();

    private static final AudioFormat AUDIO_FORMAT_OUT_MONO =
            new AudioFormat.Builder()
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .setEncoding(ENCODING)
                    .setSampleRate(SAMPLE_RATE)
                    .build();
    private static final AudioFormat AUDIO_FORMAT_IN_MONO =
            new AudioFormat.Builder()
                    .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                    .setEncoding(ENCODING)
                    .setSampleRate(SAMPLE_RATE)
                    .build();

    private static final AudioFormat AUDIO_FORMAT_STEREO =
            new AudioFormat.Builder()
                    .setChannelMask(AudioFormat.CHANNEL_IN_STEREO)
                    .setEncoding(ENCODING)
                    .setSampleRate(SAMPLE_RATE)
                    .build();


    // Audio playback and recording objects.
    private AudioTrack mAudioTrack;
    private AudioRecord mAudioRecord;
    private AudioManager mAudioManager;

    // Assistant Thread and Runnables
    private HandlerThread mAssistantThread;
    private Handler mAssistantHandler;
    private Handler mMainHandler;
    private Runnable mStartAssistantRequest;
    private Runnable mStreamAssistantRequest;
    private Runnable mStopAssistantRequest;

    // gRPC client and stream observers.
    private EmbeddedAssistantGrpc.EmbeddedAssistantStub mAssistantService;
    private StreamObserver<ConverseRequest> mAssistantRequestObserver;
    private StreamObserver<ConverseResponse> mAssistantResponseObserver;

    //pocket sphinx for hot key
    private CapTechSphinxManager captechSphinxManager;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        //ensure they are off
        toggleBlueLight(false);
        toggleRedLight(false);
        //build & start our assistant thread
        mMainHandler = new Handler(getMainLooper());
        mAssistantThread = new HandlerThread("assistantThread");
        mAssistantThread.start();
        mAssistantHandler = new Handler(mAssistantThread.getLooper());


        configureAudioSettings();

        buildAssistantRequests();

        //set up assistant service
        ManagedChannel channel = ManagedChannelBuilder.forTarget(ASSISTANT_ENDPOINT).build();
        try {
            mAssistantService = EmbeddedAssistantGrpc.newStub(channel)
                    .withCallCredentials(MoreCallCredentials.from(
                            Credentials.fromResource(this, R.raw.credentials)
                    ));
        } catch (IOException | JSONException e) {
            Log.e(TAG, "error creating assistant service:", e);
        }

        //the response to what the user is requesting...


        mAssistantResponseObserver =
                new StreamObserver<ConverseResponse>() {
                    @Override
                    public void onNext(ConverseResponse value) {
                        switch (value.getConverseResponseCase()) {
                            case EVENT_TYPE:
                                Log.d(TAG, "converse response event: " + value.getEventType());
                                if (value.getEventType() == ConverseResponse.EventType.END_OF_UTTERANCE)
                                    mAssistantHandler.post(mStopAssistantRequest);
                                break;
                            case RESULT:
                                mConversationState = value.getResult().getConversationState();
                                Log.d(TAG, value.getResult().toString());
                                mAssistantHandler.post(mStopAssistantRequest);

                                //this method will take care of if there was a volume request or not.
                                adjustVolume(value.getResult().getVolumePercentage());
                                break;
                            case AUDIO_OUT:
                                //the assistant wants to talk!
                                final ByteBuffer audioData =
                                        ByteBuffer.wrap(value.getAudioOut().getAudioData().toByteArray());
                                mAudioTrack.write(audioData, audioData.remaining(), AudioTrack.WRITE_BLOCKING);
                                break;
                            case ERROR:
                                mAssistantHandler.post(mStopAssistantRequest);
                                Log.e(TAG, "converse response error: " + value.getError());
                                break;
                        }
                    }

                    @Override
                    public void onError(Throwable t) {
                        mAssistantHandler.post(mStopAssistantRequest);
                        Log.e(TAG, "converse error:", t);
                    }

                    @Override
                    public void onCompleted() {
                        Log.d(TAG, "assistant response finished");

                    }
                };

        //instantiate PSphinx
        captechSphinxManager = new CapTechSphinxManager(this, this);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();

        stopBlinking();

        //let's clean up.
        captechSphinxManager.destroy();

        if (mAudioRecord != null) {
            mAudioRecord.stop();
            mAudioRecord = null;
        }
        if (mAudioTrack != null) {
            mAudioTrack.stop();
            mAudioTrack = null;
        }

        mAssistantHandler.post(() -> mAssistantHandler.removeCallbacks(mStreamAssistantRequest));
        mAssistantThread.quitSafely();
    }

    private void adjustVolume(int percentage) {
        if (percentage == 0)
            return;

        Log.d(TAG, "setting volume to: " + percentage);
        mAudioTrackVolume = percentage;
        float newVolume = AudioTrack.getMaxVolume() * percentage / 100.f;
        mAudioTrack.setVolume(newVolume);
    }

    /**
     * Method to contain all the audio config settings and the creation of the
     * audio manager
     */
    private void configureAudioSettings() {
        mAudioManager = (AudioManager) this.getSystemService(Context.AUDIO_SERVICE);

        //turn volume all the way up for the manager, the track will manage itself.
        int maxVolume = mAudioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
        mAudioManager.setStreamVolume(AudioManager.STREAM_MUSIC, maxVolume, 0);

        int outputBufferSize = AudioTrack.getMinBufferSize(AUDIO_FORMAT_OUT_MONO.getSampleRate(),
                AUDIO_FORMAT_OUT_MONO.getChannelMask(),
                AUDIO_FORMAT_OUT_MONO.getEncoding());

        mAudioTrack = new AudioTrack.Builder()
                .setAudioFormat(AUDIO_FORMAT_OUT_MONO)
                .setBufferSizeInBytes(outputBufferSize)
                .build();

        mAudioTrack.play();

        int inputBufferSize = AudioRecord.getMinBufferSize(AUDIO_FORMAT_STEREO.getSampleRate(),
                AUDIO_FORMAT_STEREO.getChannelMask(),
                AUDIO_FORMAT_STEREO.getEncoding());

        mAudioRecord = new AudioRecord.Builder()
                .setAudioSource(MediaRecorder.AudioSource.MIC)
                .setAudioFormat(AUDIO_FORMAT_IN_MONO)
                .setBufferSizeInBytes(inputBufferSize)
                .build();
    }

    /**
     * Method to host building the assistant request.
     * Each request runnable is responsible for either starting the request by the user,
     * streaming the users audio, or stopping the request when the user is complete.
     * <p>
     * These all run on the assistant thread.
     */
    private void buildAssistantRequests() {

        mStartAssistantRequest = () -> {
            Log.d(TAG, "starting assistant request");
            mAudioRecord.startRecording();

            mAssistantRequestObserver = mAssistantService.converse(mAssistantResponseObserver);

            ConverseConfig.Builder converseConfigBuilder =
                    ConverseConfig.newBuilder()
                            .setAudioInConfig(ASSISTANT_AUDIO_REQUEST_CONFIG)
                            .setAudioOutConfig(AudioOutConfig.newBuilder()
                                    .setEncoding(ENCODING_OUTPUT)
                                    .setSampleRateHertz(SAMPLE_RATE)
                                    .setVolumePercentage(mAudioTrackVolume)//must do this for the assistant to know it can adjust
                                    .build());

            if (mConversationState != null) {
                converseConfigBuilder.setConverseState(
                        ConverseState.newBuilder()
                                .setConversationState(mConversationState)
                                .build());
            }


            mAssistantRequestObserver.onNext(
                    ConverseRequest.newBuilder()
                            .setConfig(converseConfigBuilder.build())
                            .build());

            //start passing the recording
            mAssistantHandler.post(mStreamAssistantRequest);

            startBlinking();
        };

        mStreamAssistantRequest = () -> {
            ByteBuffer audioData = ByteBuffer.allocateDirect(SAMPLE_BLOCK_SIZE);
            int result =
                    mAudioRecord.read(audioData, audioData.capacity(), AudioRecord.READ_BLOCKING);
            if (result < 0) {
                Log.e(TAG, "error reading from audio stream:" + result);
                return;
            }
            mAssistantRequestObserver.onNext(ConverseRequest.newBuilder()
                    .setAudioIn(ByteString.copyFrom(audioData))
                    .build());
            //continue passing the recording
            mAssistantHandler.post(mStreamAssistantRequest);
        };

        mStopAssistantRequest = () -> {
            //the user is done making their request. stop passing data and clean up
            Log.d(TAG, "ending assistant request");
            mAssistantHandler.removeCallbacks(mStreamAssistantRequest);
            if (mAssistantRequestObserver != null) {
                mAssistantRequestObserver.onCompleted();
                mAssistantRequestObserver = null;
            }
            //stop recording the user
            mAudioRecord.stop();

            //start telling the user what the Assistant has to say.
            mAudioTrack.play();

            //okay we can activate via keyphrase again
            captechSphinxManager.startListeningToActivationPhrase();

            //stop the blinking lights.
            stopBlinking();

            //show blue light to indicate we are ready for another request.
            toggleBlueLight(true);
        };
    }


    @Override
    public void onInitializationComplete() {
        Log.d(TAG, "Speech Recognition Ready");
        captechSphinxManager.startListeningToActivationPhrase();
        //lets show a blue light to indicate we are ready.
        toggleBlueLight(true);
    }

    @Override
    public void onActivationPhraseDetected() {
        Log.d(TAG, "Activation Phrase Detected");
        mAssistantHandler.post(mStartAssistantRequest);
    }

    /**
     * Toggle the blue light on and off based on parameter.
     *
     * @param on
     */
    private void toggleBlueLight(boolean on) {
        PeripheralManagerService pioService = new PeripheralManagerService();
        try {
            Gpio ledGpio = pioService.openGpio(BLUE_LIGHT);
            ledGpio.setDirection(Gpio.DIRECTION_OUT_INITIALLY_LOW);
            ledGpio.setValue(on);
            blueLightOn = on;
            ledGpio.close();

        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    /**
     * Toggle the red light on and off based on parameter.
     *
     * @param on
     */
    private void toggleRedLight(boolean on) {
        PeripheralManagerService pioService = new PeripheralManagerService();
        try {
            Gpio ledGpio = pioService.openGpio(RED_LIGHT);
            ledGpio.setDirection(Gpio.DIRECTION_OUT_INITIALLY_LOW);
            ledGpio.setValue(on);
            redLightOn = on;
            ledGpio.close();

        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    /**
     * Start the blinking lights. We will alternate red and blue lights every .5 seconds
     */
    private void startBlinking() {
        blinkingTimer = new Timer();
        TimerTask mTimerTask = new TimerTask() {
            @Override
            public void run() {
                toggleRedLight(!redLightOn);
                toggleBlueLight(!blueLightOn);
            }
        };

        blinkingTimer.schedule(mTimerTask, 0, 500);
    }

    /**
     * Stop the blinking by killing the timer and turning the lights off.
     */
    private void stopBlinking() {
        if (blinkingTimer != null)
            blinkingTimer.cancel();

        //turn both lights off.
        toggleBlueLight(false);
        toggleRedLight(false);
    }

}
