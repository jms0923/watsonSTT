 /**
  * © Copyright IBM Corporation 2015
  *
  * Licensed under the Apache License, Version 2.0 (the "License");
  * you may not use this file except in compliance with the License.
  * You may obtain a copy of the License at
  *
  * http://www.apache.org/licenses/LICENSE-2.0
  *
  * Unless required by applicable law or agreed to in writing, software
  * distributed under the License is distributed on an "AS IS" BASIS,
  * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
  * See the License for the specific language governing permissions and
  * limitations under the License.
  **/

package com.ibm.watson.developer_cloud.android.examples;

import java.io.IOException;
import java.io.InputStream;
import java.io.StringWriter;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.Vector;
import java.util.regex.Matcher;

import android.annotation.TargetApi;
import android.app.FragmentTransaction;
import android.graphics.Typeface;
import android.os.AsyncTask;
import android.os.Build;
import android.text.SpannableStringBuilder;
import android.text.method.ScrollingMovementMethod;
import android.text.style.AbsoluteSizeSpan;
import android.text.util.Linkify;
import android.view.LayoutInflater;
import android.view.ViewGroup;
import android.app.ActionBar;
import android.app.Fragment;

import android.app.Activity;
import android.content.Context;
import android.os.Bundle;
import android.os.Handler;
import android.os.StrictMode;
import android.util.Log;
import android.view.Menu;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.Spinner;
import android.widget.TextView;

// IBM Watson SDK
import com.ibm.watson.developer_cloud.android.speech_to_text.v1.dto.SpeechConfiguration;
import com.ibm.watson.developer_cloud.android.speech_to_text.v1.ISpeechDelegate;
import com.ibm.watson.developer_cloud.android.speech_to_text.v1.SpeechToText;
import com.ibm.watson.developer_cloud.android.speech_common.v1.TokenProvider;

import org.apache.commons.io.IOUtils;
import org.apache.http.HttpResponse;
import org.apache.http.client.HttpClient;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.impl.client.DefaultHttpClient;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

 public class MainActivity extends Activity {

	private static final String TAG = "MainActivity";

    ActionBar.Tab tabSTT;
    FragmentTabSTT fragmentTabSTT = new FragmentTabSTT();

    public static class FragmentTabSTT extends Fragment implements ISpeechDelegate {

        private static String mRecognitionResults = "";

        private enum ConnectionState {
            IDLE, CONNECTING, CONNECTED
        }

        ConnectionState mState = ConnectionState.IDLE;
        public View mView = null;
        public Context mContext = null;
        public JSONObject jsonModels = null;
        private Handler mHandler = null;

        public View onCreateView(LayoutInflater inflater, ViewGroup container,
                                 Bundle savedInstanceState) {

            mView = inflater.inflate(R.layout.tab_stt, container, false);
            mContext = getActivity().getApplicationContext();
            mHandler = new Handler();

            setText();
            if (initSTT() == false) {
                displayResult("Error: no authentication credentials/token available, please enter your authentication information");
                return mView;
            }

            if (jsonModels == null) {
                jsonModels = new STTCommands().doInBackground();
                if (jsonModels == null) {
                    displayResult("Please, check internet connection.");
                    return mView;
                }
            }
            addItemsOnSpinnerModels();

            displayStatus("please, press the button to start speaking");

            Button buttonRecord = (Button)mView.findViewById(R.id.buttonRecord);
            buttonRecord.setOnClickListener(new View.OnClickListener() {

                @Override
                public void onClick(View arg0) {

                    if (mState == ConnectionState.IDLE) {
                        mState = ConnectionState.CONNECTING;
                        Log.d(TAG, "onClickRecord: IDLE -> CONNECTING");
                        Spinner spinner = (Spinner)mView.findViewById(R.id.spinnerModels);
                        spinner.setEnabled(false);
                        mRecognitionResults = "";
                        displayResult(mRecognitionResults);
                        ItemModel item = (ItemModel)spinner.getSelectedItem();
                        SpeechToText.sharedInstance().setModel(item.getModelName());
                        displayStatus("connecting to the STT service...");
                        // start recognition
                        new AsyncTask<Void, Void, Void>(){
                            @Override
                            protected Void doInBackground(Void... none) {
                                SpeechToText.sharedInstance().recognize();
                                return null;
                            }
                        }.execute();
                        setButtonLabel(R.id.buttonRecord, "연결중...");
                        setButtonState(true);
                    }
                    else if (mState == ConnectionState.CONNECTED) {
                        mState = ConnectionState.IDLE;
                        Log.d(TAG, "onClickRecord: CONNECTED -> IDLE");
                        Spinner spinner = (Spinner)mView.findViewById(R.id.spinnerModels);
                        spinner.setEnabled(true);
                        SpeechToText.sharedInstance().stopRecognition();
                        setButtonState(false);
                    }
                }
            });

            return mView;
        }

        private String getModelSelected() {

            Spinner spinner = (Spinner)mView.findViewById(R.id.spinnerModels);
            ItemModel item = (ItemModel)spinner.getSelectedItem();
            return item.getModelName();
        }

        public URI getHost(String url){
            try {
                return new URI(url);
            } catch (URISyntaxException e) {
                e.printStackTrace();
            }
            return null;
        }

        // initialize the connection to the Watson STT service
        private boolean initSTT() {

            // DISCLAIMER: please enter your credentials or token factory in the lines below
            String username = getString(R.string.STTUsername);
            String password = getString(R.string.STTPassword);

            String tokenFactoryURL = getString(R.string.defaultTokenFactory);
            String serviceURL = "wss://stream.watsonplatform.net/speech-to-text/api";

            SpeechConfiguration sConfig = new SpeechConfiguration(SpeechConfiguration.AUDIO_FORMAT_OGGOPUS);
            //SpeechConfiguration sConfig = new SpeechConfiguration(SpeechConfiguration.AUDIO_FORMAT_DEFAULT);
            sConfig.learningOptOut = false; // Change to true to opt-out

            SpeechToText.sharedInstance().initWithContext(this.getHost(serviceURL), getActivity().getApplicationContext(), sConfig);

            // token factory is the preferred authentication method (service credentials are not distributed in the client app)
            if (tokenFactoryURL.equals(getString(R.string.defaultTokenFactory)) == false) {
                SpeechToText.sharedInstance().setTokenProvider(new MyTokenProvider(tokenFactoryURL));
            }
            // Basic Authentication
            else if (username.equals(getString(R.string.defaultUsername)) == false) {
                SpeechToText.sharedInstance().setCredentials(username, password);
            } else {
                // no authentication method available
                return false;
            }

            SpeechToText.sharedInstance().setModel(getString(R.string.modelDefault));
            SpeechToText.sharedInstance().setDelegate(this);

            return true;
        }

        protected void setText() {

            Typeface roboto = Typeface.createFromAsset(getActivity().getApplicationContext().getAssets(), "font/Roboto-Bold.ttf");
            Typeface notosans = Typeface.createFromAsset(getActivity().getApplicationContext().getAssets(), "font/NotoSans-Regular.ttf");

            // title
            TextView viewTitle = (TextView)mView.findViewById(R.id.title);
            String strTitle = getString(R.string.sttTitle);
            SpannableStringBuilder spannable = new SpannableStringBuilder(strTitle);
            spannable.setSpan(new AbsoluteSizeSpan(47), 0, strTitle.length(), 0);
            spannable.setSpan(new CustomTypefaceSpan("", roboto), 0, strTitle.length(), 0);
            viewTitle.setText(spannable);
            viewTitle.setTextColor(0xFF325C80);

        }

        public class ItemModel {

            private JSONObject mObject = null;

            public ItemModel(JSONObject object) {
                mObject = object;
            }

            public String toString() {
                try {
                    return mObject.getString("description");
                } catch (JSONException e) {
                    e.printStackTrace();
                    return null;
                }
            }

            public String getModelName() {
                try {
                    return mObject.getString("name");
                } catch (JSONException e) {
                    e.printStackTrace();
                    return null;
                }
            }
        }

        @TargetApi(Build.VERSION_CODES.KITKAT)
        protected void addItemsOnSpinnerModels() {

            Spinner spinner = (Spinner)mView.findViewById(R.id.spinnerModels);
            int iIndexDefault = 0;

            JSONObject obj = jsonModels;
            ItemModel [] items = null;
            try {
                JSONArray models = obj.getJSONArray("models");

                // count the number of Broadband models (narrowband models will be ignored since they are for telephony data)
                Vector<Integer> v = new Vector<>();
                for (int i = 0; i < models.length(); ++i) {
                    if (models.getJSONObject(i).getString("name").indexOf("Broadband") != -1) {
                        v.add(i);
                    }
                }
                items = new ItemModel[v.size()];
                int iItems = 0;
                JSONArray tmpModels = new JSONArray();

                for (int i = 0; i < v.size(); ++i){
                    if(models.getJSONObject(v.elementAt(i)).getString("language").equals("ko-KR")){
                        JSONObject tmpObject = new JSONObject();

                        String name = "ko-KR_BroadbandModel";
                        String language = "ko-KR";
                        String url = "https://stream.watsonplatform.net/speech-to-text/api/v1/models/ko-KR_BroadbandModel";
                        int rate = 16000;

                        JSONObject supported_features = new JSONObject();
                        boolean custom_language_model = true;
                        boolean speaker_labels = false;
                        supported_features.put("custom_language_model", custom_language_model);
                        supported_features.put("speaker_labels", speaker_labels);

                        String description = "한국어";
                        Log.d("checkNumber", "korea");
                        tmpObject.put("name", name);
                        tmpObject.put("language", language);
                        tmpObject.put("url", url);
                        tmpObject.put("rate", rate);
                        tmpObject.put("supported_features", supported_features);
                        tmpObject.put("description", description);
                        tmpModels.put(tmpObject);
                        Log.d("checkModelsName", tmpModels.getJSONObject(0).toString());
                    }
                    else if(models.getJSONObject(v.elementAt(i)).getString("language").equals("fr-FR")){
                        JSONObject tmpObject = new JSONObject();
                        String name = "fr-FR_BroadbandModel";
                        String language = "fr-FR";
                        String url = "https://stream.watsonplatform.net/speech-to-text/api/v1/models/fr-FR_BroadbandModel";
                        int rate = 16000;

                        JSONObject supported_features = new JSONObject();
                        boolean custom_language_model = true;
                        boolean speaker_labels = false;
                        supported_features.put("custom_language_model", custom_language_model);
                        supported_features.put("speaker_labels", speaker_labels);

                        String description = "불어";
                        Log.d("checkNumber", "fr");
                        tmpObject.put("name", name);
                        tmpObject.put("language", language);
                        tmpObject.put("url", url);
                        tmpObject.put("rate", rate);
                        tmpObject.put("supported_features", supported_features);
                        tmpObject.put("description", description);
                        tmpModels.put(tmpObject);
                        Log.d("checkModelsName", tmpModels.getJSONObject(1).toString());
                    }
                    else if(models.getJSONObject(v.elementAt(i)).getString("language").equals("pt-BR")){
                        JSONObject tmpObject = new JSONObject();

                        String name = "pt-BR_BroadbandModel";
                        String language = "pt-BR";
                        String url = "https://stream.watsonplatform.net/speech-to-text/api/v1/models/pt-BR_BroadbandModel";
                        int rate = 16000;

                        JSONObject supported_features = new JSONObject();
                        boolean custom_language_model = true;
                        boolean speaker_labels = true;
                        supported_features.put("custom_language_model", custom_language_model);
                        supported_features.put("speaker_labels", speaker_labels);

                        String description = "포르투갈어";
                        Log.d("checkNumber", "pt-br");
                        tmpObject.put("name", name);
                        tmpObject.put("language", language);
                        tmpObject.put("url", url);
                        tmpObject.put("rate", rate);
                        tmpObject.put("supported_features", supported_features);
                        tmpObject.put("description", description);
                        tmpModels.put(tmpObject);
                        Log.d("checkModelsName", tmpModels.getJSONObject(2).toString());
                    }
                    else if(models.getJSONObject(v.elementAt(i)).getString("language").equals("zh-CN")){
                        JSONObject tmpObject = new JSONObject();

                        String name = "zh-CN_BroadbandModel";
                        String language = "zh-CN";
                        String url = "https://stream.watsonplatform.net/speech-to-text/api/v1/models/zh-CN_BroadbandModel";
                        int rate = 16000;

                        JSONObject supported_features = new JSONObject();
                        boolean custom_language_model = false;
                        boolean speaker_labels = false;
                        supported_features.put("custom_language_model", custom_language_model);
                        supported_features.put("speaker_labels", speaker_labels);

                        String description = "중국어";
                        Log.d("checkNumber", "zh-cn");
                        tmpObject.put("name", name);
                        tmpObject.put("language", language);
                        tmpObject.put("url", url);
                        tmpObject.put("rate", rate);
                        tmpObject.put("supported_features", supported_features);
                        tmpObject.put("description", description);
                        tmpModels.put(tmpObject);
                        Log.d("checkModelsName", tmpModels.getJSONObject(3).toString());
                    }
                    else if(models.getJSONObject(v.elementAt(i)).getString("language").equals("ja-JP")){
                        JSONObject tmpObject = new JSONObject();

                        String name = "ja-JP_BroadbandModel";
                        String language = "ja-JP";
                        String url = "https://stream.watsonplatform.net/speech-to-text/api/v1/models/ja-JP_BroadbandModel";
                        int rate = 16000;

                        JSONObject supported_features = new JSONObject();
                        boolean custom_language_model = true;
                        boolean speaker_labels = true;
                        supported_features.put("custom_language_model", custom_language_model);
                        supported_features.put("speaker_labels", speaker_labels);

                        String description = "일본어";
                        Log.d("checkNumber", "jp");
                        tmpObject.put("name", name);
                        tmpObject.put("language", language);
                        tmpObject.put("url", url);
                        tmpObject.put("rate", rate);
                        tmpObject.put("supported_features", supported_features);
                        tmpObject.put("description", description);
                        tmpModels.put(tmpObject);
                        Log.d("checkModelsName", tmpModels.getJSONObject(4).toString());
                    }
                    else if(models.getJSONObject(v.elementAt(i)).getString("language").equals("es-ES")){
                        JSONObject tmpObject = new JSONObject();

                        String name = "es-ES_BroadbandModel";
                        String language = "es-ES";
                        String url = "https://stream.watsonplatform.net/speech-to-text/api/v1/models/ja-JP_BroadbandModel";
                        int rate = 16000;

                        JSONObject supported_features = new JSONObject();
                        boolean custom_language_model = true;
                        boolean speaker_labels = true;
                        supported_features.put("custom_language_model", custom_language_model);
                        supported_features.put("speaker_labels", speaker_labels);

                        String description = "스페인어";
                        Log.d("checkNumber", "es-es");
                        tmpObject.put("name", name);
                        tmpObject.put("language", language);
                        tmpObject.put("url", url);
                        tmpObject.put("rate", rate);
                        tmpObject.put("supported_features", supported_features);
                        tmpObject.put("description", description);
                        tmpModels.put(tmpObject);
                        Log.d("checkModelsName", tmpModels.getJSONObject(5).toString());
                    }
                    else if(models.getJSONObject(v.elementAt(i)).getString("language").equals("ar-AR")){
                        JSONObject tmpObject = new JSONObject();

                        String name = "ar-AR_BroadbandModel";
                        String language = "ar-AR";
                        String url = "https://stream.watsonplatform.net/speech-to-text/api/v1/models/ar-AR_BroadbandModel";
                        int rate = 16000;

                        JSONObject supported_features = new JSONObject();
                        boolean custom_language_model = false;
                        boolean speaker_labels = false;
                        supported_features.put("custom_language_model", custom_language_model);
                        supported_features.put("speaker_labels", speaker_labels);

                        String description = "아랍어";
                        Log.d("checkNumber", "ar-ar");
                        tmpObject.put("name", name);
                        tmpObject.put("language", language);
                        tmpObject.put("url", url);
                        tmpObject.put("rate", rate);
                        tmpObject.put("supported_features", supported_features);
                        tmpObject.put("description", description);
                        tmpModels.put(tmpObject);
                        Log.d("checkModelsName", tmpModels.getJSONObject(6).toString());
                    }
                    else if(models.getJSONObject(v.elementAt(i)).getString("language").equals("de-DE")){
                        JSONObject tmpObject = new JSONObject();

                        String name = "de-DE_BroadbandModel";
                        String language = "de-DE";
                        String url = "https://stream.watsonplatform.net/speech-to-text/api/v1/models/de-DE_BroadbandModel";
                        int rate = 16000;

                        JSONObject supported_features = new JSONObject();
                        boolean custom_language_model = true;
                        boolean speaker_labels = false;
                        supported_features.put("custom_language_model", custom_language_model);
                        supported_features.put("speaker_labels", speaker_labels);

                        String description = "독일어";
                        Log.d("checkNumber", "de-DE");
                        tmpObject.put("name", name);
                        tmpObject.put("language", language);
                        tmpObject.put("url", url);
                        tmpObject.put("rate", rate);
                        tmpObject.put("supported_features", supported_features);
                        tmpObject.put("description", description);
                        tmpModels.put(tmpObject);
                        Log.d("checkModelsName", tmpModels.getJSONObject(7).toString());
                    }
                    else if(models.getJSONObject(v.elementAt(i)).getString("language").equals("en-GB")){
                        JSONObject tmpObject = new JSONObject();

                        String name = "en-GB_BroadbandModel";
                        String language = "en-GB";
                        String url = "https://stream.watsonplatform.net/speech-to-text/api/v1/models/en-GB_BroadbandModel";
                        int rate = 16000;

                        JSONObject supported_features = new JSONObject();
                        boolean custom_language_model = true;
                        boolean speaker_labels = false;
                        supported_features.put("custom_language_model", custom_language_model);
                        supported_features.put("speaker_labels", speaker_labels);

                        String description = "영어(영국)";
                        Log.d("checkNumber", "en-gb");
                        tmpObject.put("name", name);
                        tmpObject.put("language", language);
                        tmpObject.put("url", url);
                        tmpObject.put("rate", rate);
                        tmpObject.put("supported_features", supported_features);
                        tmpObject.put("description", description);
                        tmpModels.put(tmpObject);
                        Log.d("checkModelsName", tmpModels.getJSONObject(8).toString());
                    }
                    else if(models.getJSONObject(v.elementAt(i)).getString("language").equals("en-US")){
                        JSONObject tmpObject = new JSONObject();

                        String name = "en-US_BroadbandModel";
                        String language = "en-US";
                        String url = "https://stream.watsonplatform.net/speech-to-text/api/v1/models/en-US_BroadbandModel";
                        int rate = 16000;

                        JSONObject supported_features = new JSONObject();
                        boolean custom_language_model = true;
                        boolean speaker_labels = false;
                        supported_features.put("custom_language_model", custom_language_model);
                        supported_features.put("speaker_labels", speaker_labels);

                        String description = "영어(미국)";
                        Log.d("checkNumber", "en-us");
                        tmpObject.put("name", name);
                        tmpObject.put("language", language);
                        tmpObject.put("url", url);
                        tmpObject.put("rate", rate);
                        tmpObject.put("supported_features", supported_features);
                        tmpObject.put("description", description);
                        tmpModels.put(tmpObject);
                        Log.d("checkModelsName", tmpModels.getJSONObject(9).toString());
                    }
                }

                for (int i = 0; i < v.size() ; ++i) { // v.elementAt(i)
                    items[iItems] = new ItemModel(tmpModels.getJSONObject(i));
                    if (models.getJSONObject(v.elementAt(i)).getString("name").equals(getString(R.string.modelDefault))) {
                        iIndexDefault = iItems;
                }
                    ++iItems;
                }
            } catch (JSONException e) {
                e.printStackTrace();
            }

            if (items != null) {
		        ArrayAdapter<ItemModel> spinnerArrayAdapter = new ArrayAdapter<ItemModel>(getActivity(), android.R.layout.simple_spinner_item, items);
		        spinner.setAdapter(spinnerArrayAdapter);
                spinner.setSelection(iIndexDefault);
            }
        }  

        public void displayResult(final String result){
            final Runnable runnableUi = new Runnable(){
                @Override
                public void run() {
                    TextView textResult = (TextView)mView.findViewById(R.id.textResult);
                    textResult.setText(result);
                    textResult.setMovementMethod(new ScrollingMovementMethod());
                    setURL();
                }
            };

            new Thread(){
                public void run(){
                    mHandler.post(runnableUi);
                }
            }.start();
        }

        public void setURL(){
            TextView textResult = (TextView)mView.findViewById(R.id.textResult);
            Linkify.TransformFilter mTransform = new Linkify.TransformFilter(){
                @Override
                public String transformUrl(Matcher matcher, String url) {
                    return "";
                }
            };

            Linkify.addLinks(textResult, PatternDataBase.pattern1, "https://terms.naver.com/search.nhn?query=산성&searchType=text&dicType=&subject=", null, mTransform);
            Linkify.addLinks(textResult, PatternDataBase.pattern2, "https://terms.naver.com/search.nhn?query=염기성&searchType=text&dicType=&subject=", null, mTransform);
            Linkify.addLinks(textResult, PatternDataBase.pattern3, "https://terms.naver.com/search.nhn?query=수산화나트륨&searchType=text&dicType=&subject=", null, mTransform);
            Linkify.addLinks(textResult, PatternDataBase.pattern4, "https://terms.naver.com/search.nhn?query=기포&searchType=text&dicType=&subject=", null, mTransform);
            Linkify.addLinks(textResult, PatternDataBase.pattern5, "https://terms.naver.com/search.nhn?query=탄산칼슘&searchType=text&dicType=&subject==", null, mTransform);
            Linkify.addLinks(textResult, PatternDataBase.pattern6, "https://terms.naver.com/search.nhn?query=용액&searchType=text&dicType=&subject=", null, mTransform);
            Linkify.addLinks(textResult, PatternDataBase.pattern7, "https://terms.naver.com/search.nhn?query=단백질&searchType=text&dicType=&subject=", null, mTransform);
        }

        public void displayStatus(final String status) {
            /*final Runnable runnableUi = new Runnable(){
                @Override
                public void run() {
                    TextView textResult = (TextView)mView.findViewById(R.id.sttStatus);
                    textResult.setText(status);
                }
            };
            new Thread(){
                public void run(){
                    mHandler.post(runnableUi);
                }
            }.start();*/
        }

        /**
         * Change the button's label
         */
        public void setButtonLabel(final int buttonId, final String label) {
            final Runnable runnableUi = new Runnable(){
                @Override
                public void run() {
                    Button button = (Button)mView.findViewById(buttonId);
                    button.setText(label);
                }
            };
            new Thread(){
                public void run(){
                    mHandler.post(runnableUi);
                }
            }.start();
        }

        /**
         * Change the button's drawable
         */
        public void setButtonState(final boolean bRecording) {

            final Runnable runnableUi = new Runnable(){
                @Override
                public void run() {
                    int iDrawable = bRecording ? R.drawable.button_record_stop : R.drawable.button_record_start;
                    Button btnRecord = (Button)mView.findViewById(R.id.buttonRecord);
                    btnRecord.setBackground(getResources().getDrawable(iDrawable));
                }
            };
            new Thread(){
                public void run(){
                    mHandler.post(runnableUi);
                }
            }.start();
        }

        // delegages ----------------------------------------------

        public void onOpen() {
            Log.d(TAG, "onOpen");
            displayStatus("successfully connected to the STT service");
            setButtonLabel(R.id.buttonRecord, "멈춤");
            mState = ConnectionState.CONNECTED;
        }

        public void onError(String error) {

            Log.e(TAG, error);
            displayResult(error);
            mState = ConnectionState.IDLE;
        }

        public void onClose(int code, String reason, boolean remote) {
            Log.d(TAG, "onClose, code: " + code + " reason: " + reason);
            displayStatus("connection closed");
            setButtonLabel(R.id.buttonRecord, "시작");
            mState = ConnectionState.IDLE;
        }

        public void onMessage(String message) {

            Log.d(TAG, "onMessage, message: " + message);
            try {
                JSONObject jObj = new JSONObject(message);
                // state message
                if(jObj.has("state")) {
                    Log.d(TAG, "Status message: " + jObj.getString("state"));
                }
                // results message
                else if (jObj.has("results")) {
                    //if has result
                    Log.d(TAG, "Results message: ");
                    JSONArray jArr = jObj.getJSONArray("results");
                    for (int i=0; i < jArr.length(); i++) {
                        JSONObject obj = jArr.getJSONObject(i);
                        JSONArray jArr1 = obj.getJSONArray("alternatives");
                        String str = jArr1.getJSONObject(0).getString("transcript");
                        // remove whitespaces if the language requires it
                        String model = this.getModelSelected();
                        if (model.startsWith("ja-JP") || model.startsWith("zh-CN")) {
                            str = str.replaceAll("\\s+","");
                        }
                        String strFormatted = Character.toUpperCase(str.charAt(0)) + str.substring(1);
                        if (obj.getString("final").equals("true")) {
                            String stopMarker = (model.startsWith("ja-JP") || model.startsWith("zh-CN")) ? "。" : ". ";
                            mRecognitionResults += strFormatted.substring(0,strFormatted.length()-1) + stopMarker;

                            displayResult(mRecognitionResults);
                        } else {
                            displayResult(mRecognitionResults + strFormatted);
                        }
                        break;
                    }
                } else {
                    displayResult("unexpected data coming from stt server: \n" + message);
                }

            } catch (JSONException e) {
                Log.e(TAG, "Error parsing JSON");
                e.printStackTrace();
            }
        }

        public void onAmplitude(double amplitude, double volume) {
        }
    }

    public class MyTabListener implements ActionBar.TabListener {

        Fragment fragment;
        public MyTabListener(Fragment fragment) {
            this.fragment = fragment;
        }

        public void onTabSelected(ActionBar.Tab tab, FragmentTransaction ft) {
            ft.replace(R.id.fragment_container, fragment);
        }

        public void onTabUnselected(ActionBar.Tab tab, FragmentTransaction ft) {
            ft.remove(fragment);
        }

        public void onTabReselected(ActionBar.Tab tab, FragmentTransaction ft) {
            // nothing done here
        }
    }


    public static class STTCommands extends AsyncTask<Void, Void, JSONObject> {

        protected JSONObject doInBackground(Void... none) {

            return SpeechToText.sharedInstance().getModels();
        }
    }


    @Override
    protected void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);

		// Strictmode needed to run the http/wss request for devices > Gingerbread
		if (android.os.Build.VERSION.SDK_INT > android.os.Build.VERSION_CODES.GINGERBREAD) {
			StrictMode.ThreadPolicy policy = new StrictMode.ThreadPolicy.Builder()
					.permitAll().build();
			StrictMode.setThreadPolicy(policy);
		}

        setContentView(R.layout.activity_tab_text);

        ActionBar actionBar = getActionBar();
        actionBar.setNavigationMode(ActionBar.NAVIGATION_MODE_TABS);

        tabSTT = actionBar.newTab().setText("청각 장애인을 위한 수업 도우미");

        tabSTT.setTabListener(new MyTabListener(fragmentTabSTT));

        actionBar.addTab(tabSTT);
	}

    static class MyTokenProvider implements TokenProvider {

        String m_strTokenFactoryURL = null;

        public MyTokenProvider(String strTokenFactoryURL) {
            m_strTokenFactoryURL = strTokenFactoryURL;
        }

        public String getToken() {

            Log.d(TAG, "attempting to get a token from: " + m_strTokenFactoryURL);
            try {
                // DISCLAIMER: the application developer should implement an authentication mechanism from the mobile app to the
                // server side app so the token factory in the server only provides tokens to authenticated clients
                HttpClient httpClient = new DefaultHttpClient();
                HttpGet httpGet = new HttpGet(m_strTokenFactoryURL);
                HttpResponse executed = httpClient.execute(httpGet);
                InputStream is = executed.getEntity().getContent();
                StringWriter writer = new StringWriter();
                IOUtils.copy(is, writer, "UTF-8");
                String strToken = writer.toString();
                Log.d(TAG, strToken);
                return strToken;
            } catch (IOException e) {
                e.printStackTrace();
                return null;
            }
        }
    }

	@Override
	public boolean onCreateOptionsMenu(Menu menu) {
		getMenuInflater().inflate(R.menu.main, menu);
		return true;
	}

}
