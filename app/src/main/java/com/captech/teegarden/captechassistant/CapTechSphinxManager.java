package com.captech.teegarden.captechassistant;

import android.content.Context;
import android.os.AsyncTask;
import android.util.Log;

import java.io.File;
import java.io.IOException;

import edu.cmu.pocketsphinx.Assets;
import edu.cmu.pocketsphinx.Hypothesis;
import edu.cmu.pocketsphinx.RecognitionListener;
import edu.cmu.pocketsphinx.SpeechRecognizer;
import edu.cmu.pocketsphinx.SpeechRecognizerSetup;

import static android.content.ContentValues.TAG;
import static android.content.Intent.ACTION_SEARCH;

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

public class CapTechSphinxManager implements RecognitionListener {

    /**
     * interface for delivering life events of Sphinx to our Assistant Activity
     */
    interface SphinxListener {
        void onInitializationComplete();

        void onActivationPhraseDetected();
    }

    private static final String ACTIVATION_KEYPHRASE = "hey cap tech";
    private static final String WAKEUP_SEARCH = "wakeup";

    private final SphinxListener mSphinxListener;


    private SpeechRecognizer mSpeechRecognizer;

    public CapTechSphinxManager(Context context, SphinxListener mSphinxListener) {
        this.mSphinxListener = mSphinxListener;

        new AsyncTask<Void, Void, Exception>() {
            @Override
            protected Exception doInBackground(Void... params) {
                try {
                    Assets assets = new Assets(context);
                    File assetsDir = assets.syncAssets();

                    mSpeechRecognizer = SpeechRecognizerSetup.defaultSetup()
                            .setAcousticModel(new File(assetsDir, "en-us-ptm"))
                            .setDictionary(new File(assetsDir, "cmudict-en-us.dict"))
                            .getRecognizer();

                    mSpeechRecognizer.addListener(CapTechSphinxManager.this);

                    // Custom recognizer
                    mSpeechRecognizer.addKeyphraseSearch(WAKEUP_SEARCH, ACTIVATION_KEYPHRASE);
                    mSpeechRecognizer.addNgramSearch(ACTION_SEARCH, new File(assetsDir, "predefined.lm.bin"));
                } catch (IOException e) {
                    return e;
                }
                return null;
            }

            @Override
            protected void onPostExecute(Exception result) {
                if (result != null) {
                    Log.e(TAG, "Failed to initialize recognizer: " + result);
                } else {
                    mSphinxListener.onInitializationComplete();
                }
            }
        }.execute();
    }


    @Override
    public void onBeginningOfSpeech() {
        //we don't care about this for our use case.
    }

    /**
     * We stop recognizer here to get a final result
     */
    @Override
    public void onEndOfSpeech() {
        if (!mSpeechRecognizer.getSearchName().equals(WAKEUP_SEARCH)) {
            mSpeechRecognizer.stop();
        }
    }

    /**

     */
    @Override
    public void onPartialResult(Hypothesis hypothesis) {
        if (hypothesis == null) {
            return;
        }

        String text = hypothesis.getHypstr();
        if (text.equals(ACTIVATION_KEYPHRASE)) {
            mSpeechRecognizer.stop();
        }
    }

    /**
     * This callback is called when we stop the recognizer.
     */
    @Override
    public void onResult(Hypothesis hypothesis) {
        if (hypothesis == null) {
            return;
        }

        String text = hypothesis.getHypstr();
        if (ACTIVATION_KEYPHRASE.equals(text)) {
            mSphinxListener.onActivationPhraseDetected();
        }
    }

    @Override
    public void onError(Exception e) {
    }

    @Override
    public void onTimeout() {
        mSpeechRecognizer.stop();
    }

    /**
     * Start listening for the activation phrase.
     * To be called for by the Assistant Activity.
     */
    public void startListeningToActivationPhrase() {
        mSpeechRecognizer.startListening(WAKEUP_SEARCH);
    }


    public void destroy() {
        if (mSpeechRecognizer != null) {
            mSpeechRecognizer.cancel();
            mSpeechRecognizer.shutdown();
        }
    }

}
