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

import android.annotation.TargetApi;
import android.app.ActionBar;
import android.app.Activity;
import android.app.Fragment;
import android.app.FragmentTransaction;
import android.content.Context;
import android.graphics.Typeface;
import android.os.AsyncTask;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.StrictMode;
import android.text.SpannableString;
import android.text.SpannableStringBuilder;
import android.text.method.ScrollingMovementMethod;
import android.text.style.AbsoluteSizeSpan;
import android.text.util.Linkify;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.InputMethodManager;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.Spinner;
import android.widget.TextView;

import com.ibm.watson.developer_cloud.android.speech_common.v1.TokenProvider;
import com.ibm.watson.developer_cloud.android.speech_to_text.v1.ISpeechDelegate;
import com.ibm.watson.developer_cloud.android.speech_to_text.v1.SpeechToText;
import com.ibm.watson.developer_cloud.android.speech_to_text.v1.dto.SpeechConfiguration;
import com.ibm.watson.developer_cloud.android.text_to_speech.v1.TextToSpeech;

import org.apache.commons.io.IOUtils;
import org.apache.http.HttpResponse;
import org.apache.http.client.HttpClient;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.impl.client.DefaultHttpClient;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.io.InputStream;
import java.io.StringWriter;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.Vector;
import java.util.regex.Matcher;

// IBM Watson SDK
public class MainActivity extends Activity {

	private static final String TAG = "MainActivity";                                                                   // TAG는 디버깅용 변수.

    TextView textTTS;

    ActionBar.Tab tabSTT, tabTTS;
    FragmentTabSTT fragmentTabSTT = new FragmentTabSTT();
    FragmentTabTTS fragmentTabTTS = new FragmentTabTTS();

    public static class FragmentTabSTT extends Fragment implements ISpeechDelegate {                                    // onCreate 에서 생성하는 탭 클래스.

        private static String mRecognitionResults = "";

        private enum ConnectionState {                                                                                  // 상태를 저장하는 변수들 - 차례로 : 끊김, 연결중, 연결됨.
            IDLE, CONNECTING, CONNECTED
        }

        ConnectionState mState = ConnectionState.IDLE;
        public View mView = null;
        public Context mContext = null;
        public JSONObject jsonModels = null;
        private Handler mHandler = null;

        public View onCreateView(LayoutInflater inflater, ViewGroup container,                                          // onCreate 이후에 화면을 구성할때 사용.
                                 Bundle savedInstanceState) {                                                           // Bundle은 상태를 저장하는 변수 - savedInstanceState은 액티비티가 종료되어도 액티비티의 데이터를 저장할 수 있음.

            mView = inflater.inflate(R.layout.tab_stt, container, false);                                 // res -> layout -> tab_stt.xml 불러옴.
            mContext = getActivity().getApplicationContext();
            mHandler = new Handler();                                                                                   // Handler는 해당 Handler를 호출한 스레드의 Message Queue와 Looper에 자동 연결.

            setText();
            if (initSTT() == false) {                                                                                   // initSTT는 api의 사용 권한을 허락 받음.
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
            addItemsOnSpinnerModels();                                                                                  // 영어로된 언어 목록을 받아오면 한글로 바꿔줌.

            displayStatus("please, press the button to start speaking");

            Button buttonRecord = (Button)mView.findViewById(R.id.buttonRecord);                                        // tab_stt.xml 에서 "시작" 버튼 가져옴.
            buttonRecord.setOnClickListener(new View.OnClickListener() {                                                // 가져온 시작 버튼에 리스너 객체.

                @Override
                public void onClick(View arg0) {                                                                        // 버튼 클릭시 실행.

                    if (mState == ConnectionState.IDLE) {                                                               // 아무것도 안하고 있을때 (IDLE) -> 연결 시작 (최초 상태, 버튼이 "시작"이라고 적혀있을 때).
                        mState = ConnectionState.CONNECTING;
                        Log.d(TAG, "onClickRecord: IDLE -> CONNECTING");                                          // 디버깅용 메세지.
                        Spinner spinner = (Spinner)mView.findViewById(R.id.spinnerModels);
                        spinner.setEnabled(false);
                        mRecognitionResults = "";
                        displayResult(mRecognitionResults);
                        ItemModel item = (ItemModel)spinner.getSelectedItem();
                        SpeechToText.sharedInstance().setModel(item.getModelName());
                        displayStatus("connecting to the STT service...");
                        // start recognition
                        new AsyncTask<Void, Void, Void>(){                                                              // AsyncTask -> UI를 변경할 수 있는 스레드.
                            @Override
                            protected Void doInBackground(Void... none) {
                                SpeechToText.sharedInstance().recognize();                                              // (api) 사용 권한 가져오는 함수.
                                return null;                                                                            // 스레드 종료.
                            }
                        }.execute();
                        setButtonLabel(R.id.buttonRecord, "연결중...");
                        setButtonState(true);
                    }
                    else if (mState == ConnectionState.CONNECTED) {                                                     // 연결 되어 있는 경우 (CONNECTED) -> 연결 종료. (버튼이 "멈춤"일때)
                        mState = ConnectionState.IDLE;
                        Log.d(TAG, "onClickRecord: CONNECTED -> IDLE");
                        Spinner spinner = (Spinner)mView.findViewById(R.id.spinnerModels);                              // 언어 목록 가져옴.
                        spinner.setEnabled(true);                                                                       // 언어 목록 선택 불가.
                        SpeechToText.sharedInstance().stopRecognition();                                                // 녹음 중단.
                        setButtonState(false);                                                                          // 버튼 상태 변경.
                    }
                }
            });

            return mView;
        }                                                                                                               // onCreateView 종료.

        private String getModelSelected() {

            Spinner spinner = (Spinner)mView.findViewById(R.id.spinnerModels);                                          // 모델 명(언어 목록) 선택.
            ItemModel item = (ItemModel)spinner.getSelectedItem();                                                      // 언어 목록들 가져옴.
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
        private boolean initSTT() {                                                                                                             // api의 사용 권한을 ibm으로 부터 허락 받음.

            // DISCLAIMER: please enter your credentials or token factory in the lines below
            String username = getString(R.string.STTUsername);
            String password = getString(R.string.STTPassword);                                                                                  // res -> string -> name=STTUsername 인 태그의 데이터를 가져옴 -> APIKey

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
                return false;                                                                                                                   // 권한 가져오기 실패.
            }

            SpeechToText.sharedInstance().setModel(getString(R.string.modelDefault));                                                           // 기본 언어를 한글로 설정. (api)
            SpeechToText.sharedInstance().setDelegate(this);

            return true;                                                                                                                        // 권한 가져오기 성공.
        }

        protected void setText() {

            Typeface roboto = Typeface.createFromAsset(getActivity().getApplicationContext().getAssets(), "font/Roboto-Bold.ttf");        // 글씨체 가져오기 assets -> font -> Roboto-Bold.ttf
            Typeface notosans = Typeface.createFromAsset(getActivity().getApplicationContext().getAssets(), "font/NotoSans-Regular.ttf");

            // title
            TextView viewTitle = (TextView)mView.findViewById(R.id.title);                                                                      // 현재 액티비티의 UI (tab_stt.xml)의 title 가져옴.
            String strTitle = getString(R.string.sttTitle);                                                                                     // res -> strings.xml -> sttTitle 가져옴.
            SpannableStringBuilder spannable = new SpannableStringBuilder(strTitle);                                                            // SpannableStringBuilder(strTitle) -> strTitle의 일부의 문자 스타일을 변경.
            spannable.setSpan(new AbsoluteSizeSpan(47), 0, strTitle.length(), 0);
            spannable.setSpan(new CustomTypefaceSpan("", roboto), 0, strTitle.length(), 0);
            viewTitle.setText(spannable);                                                                                                       // viewTitle의 글자를 spannable로 변경.
            viewTitle.setTextColor(0xFF325C80);                                                                                                 // 글자 색 변경.

        }

        public class ItemModel {                                                                                                                // 언어 목록을 가져오기 위한 객체.

            private JSONObject mObject = null;

            public ItemModel(JSONObject object) {                                                                                               // 언어 목록을 저장하기 위한 객체.
                mObject = object;
            }

            public String toString() {                                                                                                          // 언어 목록에서 "description"에 해당하는 내용 가져옴.
                try {
                    return mObject.getString("description");
                } catch (JSONException e) {
                    e.printStackTrace();
                    return null;
                }
            }

            public String getModelName() {                                                                                                      // 언어 목록에서 "name"에 해당하는 내용 가져옴.
                try {
                    return mObject.getString("name");
                } catch (JSONException e) {
                    e.printStackTrace();
                    return null;
                }
            }
        }

        @TargetApi(Build.VERSION_CODES.KITKAT)
        protected void addItemsOnSpinnerModels() {                                                                                          // 언어 목록 가져와서 필요한 정보만 언어 목록에 추가해 주는 함수.

            Spinner spinner = (Spinner)mView.findViewById(R.id.spinnerModels);                                                              // 언어 목록 가져옴.
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

                items = new ItemModel[10]; // v.size()
                int iItems = 0;
                JSONArray tmpModels = new JSONArray();

//                Log.d("size test", Integer.toString(v.size()));
                for (int i = 0; i < v.size(); ++i){// v.size()
                    if(models.getJSONObject(v.elementAt(i)).getString("language").equals("ko-KR")){                                 // api로 가져온 언어 정보들을 언어별로 따로 정리.
//                        Log.d("size test", String.valueOf(models.getJSONObject(v.elementAt(i))));
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

                        String description = "한국어";                                                                                     // 언어 목록에서 실제로 띄어질 부분을 한글로 변경.
                        Log.d("checkNumber", "korea");
                        tmpObject.put("name", name);
                        tmpObject.put("language", language);
                        tmpObject.put("url", url);
                        tmpObject.put("rate", rate);
                        tmpObject.put("supported_features", supported_features);
                        tmpObject.put("description", description);
                        tmpModels.put(tmpObject);
//                        Log.d("checkModelsName", tmpModels.getJSONObject(0).toString());
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
//                        Log.d("checkModelsName", tmpModels.getJSONObject(1).toString());
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
//                        Log.d("checkModelsName", tmpModels.getJSONObject(2).toString());
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
//                        Log.d("checkModelsName", tmpModels.getJSONObject(3).toString());
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
//                        Log.d("checkModelsName", tmpModels.getJSONObject(4).toString());
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
//                        Log.d("checkModelsName", tmpModels.getJSONObject(5).toString());
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
//                        Log.d("checkModelsName", tmpModels.getJSONObject(6).toString());
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
//                        Log.d("checkModelsName", tmpModels.getJSONObject(7).toString());
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
//                        Log.d("checkModelsName", tmpModels.getJSONObject(8).toString());
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
//                        Log.d("checkModelsName", tmpModels.getJSONObject(9).toString());
                    }
                }

                for (int i = 0; i < 10 ; ++i) {     // v.size()                                                                                      // 기본 언어를 한글로 변경.
                    items[iItems] = new ItemModel(tmpModels.getJSONObject(i));
                    if (models.getJSONObject(v.elementAt(i)).getString("name").equals(getString(R.string.modelDefault))) {              // modelDefault -> 한글로 설정 되어 있음.
                        iIndexDefault = iItems;
                }
                    ++iItems;
                }
            } catch (JSONException e) {
                e.printStackTrace();
            }

            if (items != null) {                                                                                                                        // UI의 언어 목록을 api에서 가져온 언어목록들로 채움.
		        ArrayAdapter<ItemModel> spinnerArrayAdapter = new ArrayAdapter<ItemModel>(getActivity(), android.R.layout.simple_spinner_item, items);  // layout -> simple_spinner_item을 가져옴.
		        spinner.setAdapter(spinnerArrayAdapter);
                spinner.setSelection(iIndexDefault);                                                                                                    // api를 통해 가져온 언어 목록들로 채움.
            }
        }  

        public void displayResult(final String result){                                                                                                 // api를 통해 가져온 결과(음성언어 -> 글자)를 UI에 적어줌.
            final Runnable runnableUi = new Runnable(){
                @Override
                public void run() {
                    TextView textResult = (TextView)mView.findViewById(R.id.textResult);                                                                // layout -> textResult 가져옴.
                    textResult.setText(result);                                                                                                         // 글자 적어줌.
                    textResult.setMovementMethod(new ScrollingMovementMethod());                                                                        // 스크롤 가능하게 해줌.
                    setURL();                                                                                                                           // 링크 걸어줄께 있나 확인.
                }
            };

            new Thread(){
                public void run(){
                    mHandler.post(runnableUi);
                }
            }.start();
        }

        public void setURL(){                                                                                   // 링크 걸어주는 함수.
            TextView textResult = (TextView)mView.findViewById(R.id.textResult);                                // 녹음 시 글자가 적히는 부분을 가져옴.
            Linkify.TransformFilter mTransform = new Linkify.TransformFilter(){
                @Override
                public String transformUrl(Matcher matcher, String url) {
                    return "";
                }
            };

            Linkify.addLinks(textResult, PatternDataBase.pattern1, "https://terms.naver.com/search.nhn?query=산성&searchType=text&dicType=&subject=", null, mTransform);              // 저장된 패턴과 일치하는 확인 후, 링크를 걸어줌.
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
        public void setButtonLabel(final int buttonId, final String label) {                                        // 버튼 글자를 바꿈.
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

            final Runnable runnableUi = new Runnable(){                                                                 // Runnable -> 스레드가 어떻게 실행할지 정의해 놓은 것.
                @Override
                public void run() {                                                                                     // 녹음 버튼 상태를 바꿈. (색깔)
                    int iDrawable = bRecording ? R.drawable.button_record_stop : R.drawable.button_record_start;
                    Button btnRecord = (Button)mView.findViewById(R.id.buttonRecord);
                    btnRecord.setBackground(getResources().getDrawable(iDrawable));
                }
            };
            new Thread(){
                public void run(){
                    mHandler.post(runnableUi);
                }
            }.start();                                                                                                  // 스레드 시작.
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

    public static class FragmentTabTTS extends Fragment {

         public View mView = null;
         public Context mContext = null;
         public JSONObject jsonVoices = null;
         private Handler mHandler = null;

         public View onCreateView(LayoutInflater inflater, ViewGroup container,
                                  Bundle savedInstanceState) {
             Log.d(TAG, "onCreateTTS");
             mView = inflater.inflate(R.layout.tab_tts, container, false);
             mContext = getActivity().getApplicationContext();

             setText();
             if (initTTS() == false) {
                 TextView viewPrompt = (TextView) mView.findViewById(R.id.prompt);
                 viewPrompt.setText("Error: no authentication credentials or token available, please enter your authentication information");
                 return mView;
             }

             if (jsonVoices == null) {
                 jsonVoices = new TTSCommands().doInBackground();
                 if (jsonVoices == null) {
                     return mView;
                 }
             }
             addItemsOnSpinnerVoices();
             updatePrompt(getString(R.string.voiceDefault));

             Spinner spinner = (Spinner) mView.findViewById(R.id.spinnerVoices);
             spinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
                 @Override
                 public void onItemSelected(AdapterView<?> parentView, View selectedItemView, int position, long id) {

                     Log.d(TAG, "setOnItemSelectedListener");
                     final Runnable runnableUi = new Runnable() {
                         @Override
                         public void run() {
                             FragmentTabTTS.this.updatePrompt(FragmentTabTTS.this.getSelectedVoice());
                         }
                     };
                     new Thread() {
                         public void run() {
                             mHandler.post(runnableUi);
                         }
                     }.start();
                 }

                 @Override
                 public void onNothingSelected(AdapterView<?> parentView) {
                     // your code here
                 }
             });

             mHandler = new Handler();
             return mView;
         }

         public URI getHost(String url){
             try {
                 return new URI(url);
             } catch (URISyntaxException e) {
                 e.printStackTrace();
             }
             return null;
         }

         private boolean initTTS() {

             // DISCLAIMER: please enter your credentials or token factory in the lines below

             String username = getString(R.string.TTSUsername);
             String password = getString(R.string.TTSPassword);
             String tokenFactoryURL = getString(R.string.TtsTokenFactory);
             String serviceURL = "https://stream.watsonplatform.net/text-to-speech/api";

             TextToSpeech.sharedInstance().initWithContext(this.getHost(serviceURL));

             // token factory is the preferred authentication method (service credentials are not distributed in the client app)
             if (tokenFactoryURL.equals(getString(R.string.TtsTokenFactory)) == false) {
                 TextToSpeech.sharedInstance().setTokenProvider(new MyTokenProvider(tokenFactoryURL));
             }
             // Basic Authentication
             else if (username.equals(getString(R.string.TtsTokenFactory)) == false) {
                 TextToSpeech.sharedInstance().setCredentials(username, password);
             } else {
                 // no authentication method available
                 return false;
             }
             TextToSpeech.sharedInstance().setLearningOptOut(false); // Change to true to opt-out

             TextToSpeech.sharedInstance().setVoice(getString(R.string.voiceDefault));

             return true;
         }

         protected void setText() {

             Typeface roboto = Typeface.createFromAsset(getActivity().getApplicationContext().getAssets(), "font/Roboto-Bold.ttf");
//             Typeface notosans = Typeface.createFromAsset(getActivity().getApplicationContext().getAssets(), "font/NotoSans-Regular.ttf");

             TextView viewTitle = (TextView)mView.findViewById(R.id.title);
             String strTitle = getString(R.string.ttsTitle);
             SpannableString spannable = new SpannableString(strTitle);
             spannable.setSpan(new AbsoluteSizeSpan(47), 0, strTitle.length(), 0);
             spannable.setSpan(new CustomTypefaceSpan("", roboto), 0, strTitle.length(), 0);
             viewTitle.setText(spannable);
             viewTitle.setTextColor(0xFF325C80);

         }

         public class ItemVoice {

             public JSONObject mObject = null;

             public ItemVoice(JSONObject object) {
                 mObject = object;
             }

             public String toString() {
                 try {
                     return mObject.getString("name");
                 } catch (JSONException e) {
                     e.printStackTrace();
                     return null;
                 }
             }
         }

         public void addItemsOnSpinnerVoices() {

             Spinner spinner = (Spinner)mView.findViewById(R.id.spinnerVoices);
             int iIndexDefault = 0;

             JSONObject obj = jsonVoices;
             ItemVoice [] items = null;
             try {
                 JSONArray voices = obj.getJSONArray("voices");
                 items = new ItemVoice[voices.length()];

                 for (int i = 0; i < voices.length(); ++i) {
                     String tmpVoice = voices.getJSONObject(i).getString("name");
                     voices.getJSONObject(i).remove("name");

                     switch(tmpVoice){
                         case "pt-BR_IsabelaVoice":
                             voices.getJSONObject(i).put("name", "브라질 포르투갈어 여성 1");
                             break;
                         case "pt-BR_IsabelaV2Voice":
                             voices.getJSONObject(i).put("name", "브라질 포르투갈어 여성 2");
                             break;
                         case "pt-BR_IsabelaV3Voice":
                             voices.getJSONObject(i).put("name", "브라질 포르투갈어 여성 3");
                             break;

                         case "es-ES_EnriqueVoice":
                             voices.getJSONObject(i).put("name", "카스티야 스페인어 남성 1");
                             break;
                         case "es-ES_EnriqueV2Voice":
                             voices.getJSONObject(i).put("name", "카스티야 스페인어 남성 2");
                             break;
                         case "es-ES_EnriqueV3Voice":
                             voices.getJSONObject(i).put("name", "카스티야 스페인어 남성 3");
                             break;
                         case "es-ES_LauraVoice":
                             voices.getJSONObject(i).put("name", "카스티야 스페인어 여성 1");
                             break;
                         case "es-ES_LauraV2Voice":
                             voices.getJSONObject(i).put("name", "카스티야 스페인어 여성 2");
                             break;
                         case "es-ES_LauraV3Voice":
                             voices.getJSONObject(i).put("name", "카스티야 스페인어 여성 3");
                             break;

                         case "fr-FR_ReneeVoice":
                             voices.getJSONObject(i).put("name", "프랑스어 여성 1");
                             break;
                         case "fr-FR_ReneeV2Voice":
                             voices.getJSONObject(i).put("name", "프랑스어 여성 2");
                             break;
                         case "fr-FR_ReneeV3Voice":
                             voices.getJSONObject(i).put("name", "프랑스어 여성 3");
                             break;

                         case "de-DE_BirgitVoice":
                             voices.getJSONObject(i).put("name", "독일어 여성 1");
                             break;
                         case "de-DE_BirgitV2Voice":
                             voices.getJSONObject(i).put("name", "독일어 여성 2");
                             break;
                         case "de-DE_BirgitV3Voice":
                             voices.getJSONObject(i).put("name", "독일어 여성 3");
                             break;
                         case "de-DE_DieterVoice":
                             voices.getJSONObject(i).put("name", "독일어 남성 1");
                             break;
                         case "de-DE_DieterV2Voice":
                             voices.getJSONObject(i).put("name", "독일어 남성 2");
                             break;
                         case "de-DE_DieterV3Voice":
                             voices.getJSONObject(i).put("name", "독일어 남성 3");
                             break;

                         case "it-IT_FrancescaVoice":
                             voices.getJSONObject(i).put("name", "이탈리아어 여성 1");
                             break;
                         case "it-IT_FrancescaV2Voice":
                             voices.getJSONObject(i).put("name", "이탈리아어 여성 2");
                             break;
                         case "it-IT_FrancescaV3Voice":
                             voices.getJSONObject(i).put("name", "이탈리아어 여성 3");
                             break;

                         case "ja-JP_EmiVoice":
                             voices.getJSONObject(i).put("name", "일본어 여성 1");
                             break;
                         case "ja-JP_EmiV2Voice":
                             voices.getJSONObject(i).put("name", "일본어 여성 2");
                             break;
                         case "ja-JP_EmiV3Voice":
                             voices.getJSONObject(i).put("name", "일본어 여성 3");
                             break;

                         case "es-LA_SofiaVoice":
                             voices.getJSONObject(i).put("name", "라틴 아메리카 스페인어 여성 1");
                             break;
                         case "es-LA_SofiaV2Voice":
                             voices.getJSONObject(i).put("name", "라틴 아메리카 스페인어 여성 2");
                             break;
                         case "es-LA_SofiaV3Voice":
                             voices.getJSONObject(i).put("name", "라틴 아메리카 스페인어 여성 3");
                             break;


                         case "es-US_SofiaVoice":
                             voices.getJSONObject(i).put("name", "북아메리카 스페인어 여성 1");
                             break;
                         case "es-US_SofiaV2Voice":
                             voices.getJSONObject(i).put("name", "북아메리카 스페인어 여성 2");
                             break;
                         case "es-US_SofiaV3Voice":
                             voices.getJSONObject(i).put("name", "북아메리카 스페인어 여성 3");
                             break;

                         case "en-GB_KateVoice":
                             voices.getJSONObject(i).put("name", "영국 영어 여성 1");
                             break;
                         case "en-GB_KateV2Voice":
                             voices.getJSONObject(i).put("name", "영국 영어 여성 2");
                             break;
                         case "en-GB_KateV3Voice":
                             voices.getJSONObject(i).put("name", "영국 영어 여성 3");
                             break;

                         case "en-US_AllisonVoice":
                             voices.getJSONObject(i).put("name", "미국 영어 여성 1");
                             break;
                         case "en-US_AllisonV2Voice":
                             voices.getJSONObject(i).put("name", "미국 영어 여성 2");
                             break;
                         case "en-US_AllisonV3Voice":
                             voices.getJSONObject(i).put("name", "미국 영어 여성 3");
                             break;
                         case "en-US_LisaVoice":
                             voices.getJSONObject(i).put("name", "미국 영어 여성 4");
                             break;
                         case "en-US_LisaV2Voice":
                             voices.getJSONObject(i).put("name", "미국 영어 여성 5");
                             break;
                         case "en-US_LisaV3Voice":
                             voices.getJSONObject(i).put("name", "미국 영어 여성 6");
                             break;
                         case "en-US_MichaelVoice":
                             voices.getJSONObject(i).put("name", "미국 영어 남성 1");
                             break;
                         case "en-US_MichaelV2Voice":
                             voices.getJSONObject(i).put("name", "미국 영어 남성 2");
                             break;
                         case "en-US_MichaelV3Voice":
                             voices.getJSONObject(i).put("name", "미국 영어 남성 3");
                             break;

                         default:
                             voices.getJSONObject(i).put("name", tmpVoice);
                             break;
                     }

                     items[i] = new ItemVoice(voices.getJSONObject(i));
                     if (voices.getJSONObject(i).getString("name").equals(getString(R.string.voiceDefault))) {
                         iIndexDefault = i;
                     }
                 }
             } catch (JSONException e) {
                 e.printStackTrace();
             }
             if (items != null) {
                 ArrayAdapter<ItemVoice> spinnerArrayAdapter = new ArrayAdapter<ItemVoice>(getActivity(), android.R.layout.simple_spinner_item, items);
                 spinner.setAdapter(spinnerArrayAdapter);
                 spinner.setSelection(iIndexDefault);
             }
         }

         // return the selected voice
         public String getSelectedVoice() {

             // return the selected voice
             Spinner spinner = (Spinner)mView.findViewById(R.id.spinnerVoices);
             ItemVoice item = (ItemVoice)spinner.getSelectedItem();
             String strVoice = null;
             try {
                 strVoice = item.mObject.getString("name");
                 switch(strVoice){
                     case "브라질 포르투갈어 여성 1":
                         strVoice = "pt-BR_IsabelaVoice";
                         break;
                     case "브라질 포르투갈어 여성 2":
                         strVoice = "pt-BR_IsabelaV2Voice";
                         break;
                     case "브라질 포르투갈어 여성 3":
                         strVoice = "pt-BR_IsabelaV3Voice";
                         break;

                     case "카스티야 스페인어 남성 1":
                         strVoice = "es-ES_EnriqueVoice";
                         break;
                     case "카스티야 스페인어 남성 2":
                         strVoice = "es-ES_EnriqueV2Voice";
                         break;
                     case "카스티야 스페인어 남성 #":
                         strVoice = "es-ES_EnriqueV3Voice";
                         break;
                     case "카스티야 스페인어 여성 1":
                         strVoice = "es-ES_LauraVoice";
                         break;
                     case "카스티야 스페인어 여성 2":
                         strVoice = "es-ES_LauraV2Voice";
                         break;
                     case "카스티야 스페인어 여성 3":
                         strVoice = "es-ES_LauraV3Voice";
                         break;

                     case "프랑스어 여성 1":
                         strVoice = "fr-FR_ReneeVoice";
                         break;
                     case "프랑스어 여성 2":
                         strVoice = "fr-FR_ReneeV2Voice";
                         break;
                     case "프랑스어 여성 3":
                         strVoice = "fr-FR_ReneeV3Voice";
                         break;

                     case "독일어 여성 1":
                         strVoice = "de-DE_BirgitVoice";
                         break;
                     case "독일어 여성 2":
                         strVoice = "de-DE_BirgitV2Voice";
                         break;
                     case "독일어 여성 3":
                         strVoice = "de-DE_BirgitV3Voice";
                         break;

                     case "독일어 남성 1":
                         strVoice = "de-DieterVoice";
                         break;
                     case "독일어 남성 2":
                         strVoice = "de-DieterV2Voice";
                         break;
                     case "독일어 남성 3":
                         strVoice = "de-DieterV3Voice";
                         break;

                     case "이탈리아어 여성 1":
                         strVoice = "it-IT_FrancescaVoice";
                         break;
                     case "이탈리아어 여성 2":
                         strVoice = "it-IT_FrancescaV2Voice";
                         break;
                     case "이탈리아어 여성 3":
                         strVoice = "it-IT_FrancescaV3Voice";
                         break;

                     case "일본어 여성 1":
                         strVoice = "ja-JP_EmiVoice";
                         break;
                     case "일본어 여성 2":
                         strVoice = "ja-JP_EmiV2Voice";
                         break;
                     case "일본어 여성 3":
                         strVoice = "ja-JP_EmiV3Voice";
                         break;

                     case "라틴 아메리카 스페인어 여성 1":
                         strVoice = "es-LA_SofiaVoice";
                         break;
                     case "라틴 아메리카 스페인어 여성 2":
                         strVoice = "es-LA_SofiaV2Voice";
                         break;
                     case "라틴 아메리카 스페인어 여성 3":
                         strVoice = "es-LA_SofiaV3Voice";
                         break;

                     case "북아메리카 아메리카 스페인어 여성 1":
                         strVoice = "es-US_SofiaVoice";
                         break;
                     case "북아메리카 아메리카 스페인어 여성 2":
                         strVoice = "es-US_SofiaV2Voice";
                         break;
                     case "북아메리카 아메리카 스페인어 여성 3":
                         strVoice = "es-US_SofiaV3Voice";
                         break;

                     case "영국 영어 여성 1":
                         strVoice = "en-GB_KateVoice";
                         break;
                     case "영국 영어 여성 2":
                         strVoice = "en-GB_KateV2Voice";
                         break;
                     case "영국 영어 여성 3":
                         strVoice = "en-GB_KateV3Voice";
                         break;

                     case "미국 영어 여성 1":
                         strVoice = "en-US_AllisonVoice";
                         break;
                     case "미국 영어 여성 2":
                         strVoice = "en-US_AllisonV2Voice";
                         break;
                     case "미국 영어 여성 3":
                         strVoice = "en-US_AllisonV3Voice";
                         break;
                     case "미국 영어 여성 4":
                         strVoice = "en-US_LisaVoice";
                         break;
                     case "미국 영어 여성 5":
                         strVoice = "en-US_LisaV2Voice";
                         break;
                     case "미국 영어 여성 ^":
                         strVoice = "en-US_LisaV3Voice";
                         break;

                     case "미국 영어 남성 1":
                         strVoice = "en-US_MichaelVoice";
                         break;
                     case "미국 영어 남성 2":
                         strVoice = "en-US_MichaelV2Voice";
                         break;
                     case "미국 영어 남성 3":
                         strVoice = "en-US_MichaelV3Voice";
                         break;

                     default:
                         break;
                 }

             } catch (JSONException e) {
                 e.printStackTrace();
             }
             return strVoice;
         }

         // update the prompt for the selected voice
         public void updatePrompt(final String strVoice) {

             TextView viewPrompt = (TextView)mView.findViewById(R.id.prompt);
             if (strVoice.startsWith("en-US") || strVoice.startsWith("en-GB")) {
                 viewPrompt.setText(getString(R.string.ttsEnglishPrompt));
             } else if (strVoice.startsWith("es-ES")) {
                 viewPrompt.setText(getString(R.string.ttsSpanishPrompt));
             } else if (strVoice.startsWith("fr-FR")) {
                 viewPrompt.setText(getString(R.string.ttsFrenchPrompt));
             } else if (strVoice.startsWith("it-IT")) {
                 viewPrompt.setText(getString(R.string.ttsItalianPrompt));
             } else if (strVoice.startsWith("de-DE")) {
                 viewPrompt.setText(getString(R.string.ttsGermanPrompt));
             } else if (strVoice.startsWith("ja-JP")) {
                 viewPrompt.setText(getString(R.string.ttsJapanesePrompt));
             }
         }
     }

    public class MyTabListener implements ActionBar.TabListener {                                                   // 리스너 객체 생성시 호출 됨.

        Fragment fragment;
        public MyTabListener(Fragment fragment) {                                           // 리스너 객체 생성자
            this.fragment = fragment;
        }

        public void onTabSelected(ActionBar.Tab tab, FragmentTransaction ft) {              // 해당 탭이 선택 되었을때
            ft.replace(R.id.fragment_container, fragment);
        }

        public void onTabUnselected(ActionBar.Tab tab, FragmentTransaction ft) {            // 다른 탭 선택 될때
            ft.remove(fragment);
        }

        public void onTabReselected(ActionBar.Tab tab, FragmentTransaction ft) {            // 해당 탭 재선택 될때
            // nothing done here
        }
    }


    public static class STTCommands extends AsyncTask<Void, Void, JSONObject> {

        protected JSONObject doInBackground(Void... none) {

            return SpeechToText.sharedInstance().getModels();                                                           // 모델(언어 목록) 불러옴. (API)
        }
    }

    public static class TTSCommands extends AsyncTask<Void, Void, JSONObject> {

         protected JSONObject doInBackground(Void... none) {

             return TextToSpeech.sharedInstance().getVoices();
         }
     }


     @Override
    protected void onCreate(Bundle savedInstanceState) {                                                                // 메인 엑티비티 생성 시 호출 - 메인 엑티비티가 생성되는 것.
		super.onCreate(savedInstanceState);

		// Strictmode needed to run the http/wss request for devices > Gingerbread
		if (android.os.Build.VERSION.SDK_INT > android.os.Build.VERSION_CODES.GINGERBREAD) {                            // 안드로이드 버전 확인해서 정책 설정.
			StrictMode.ThreadPolicy policy = new StrictMode.ThreadPolicy.Builder()
					.permitAll().build();
			StrictMode.setThreadPolicy(policy);
		}

        setContentView(R.layout.activity_tab_text);                                                                    // res-layout-activity_tab_text.xml 불러옴.

        ActionBar actionBar = getActionBar();                                                                          // 액션 바 생성.
        actionBar.setNavigationMode(ActionBar.NAVIGATION_MODE_TABS);

        tabSTT = actionBar.newTab().setText("음성 -> 문자");                                         // 탭 생성 및 이름 설정.    ->   fragmentTabSTT 객체
        tabTTS = actionBar.newTab().setText("문자 -> 음성");                                                                                                               // Fragment == small module of Activity,

        tabSTT.setTabListener(new MyTabListener(fragmentTabSTT));                                                       // 리스터 생성.   ->   MyTabListener 객체
        tabTTS.setTabListener(new MyTabListener(fragmentTabTTS));

        actionBar.addTab(tabSTT);                                                                                       // 액션 바에 탭 추가.
        actionBar.addTab(tabTTS);
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
	public boolean onCreateOptionsMenu(Menu menu) {                                                                     // 옵션 메뉴 생성.
		getMenuInflater().inflate(R.menu.main, menu);
		return true;
	}

    public void playTTS(View view) throws JSONException {

         TextToSpeech.sharedInstance().setVoice(fragmentTabTTS.getSelectedVoice());
         Log.d(TAG, fragmentTabTTS.getSelectedVoice());

         //Get text from text box
         textTTS = (TextView)fragmentTabTTS.mView.findViewById(R.id.prompt);
         String ttsText=textTTS.getText().toString();
         Log.d(TAG, ttsText);
         InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
         imm.hideSoftInputFromWindow(textTTS.getWindowToken(),
                 InputMethodManager.HIDE_NOT_ALWAYS);

         //Call the sdk function
         TextToSpeech.sharedInstance().synthesize(ttsText);
     }


 }
