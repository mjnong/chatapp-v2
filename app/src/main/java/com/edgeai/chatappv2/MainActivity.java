package com.edgeai.chatappv2;

import androidx.activity.result.ActivityResultCallback;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.lifecycle.ViewModelProvider;
import android.content.Intent;
import android.os.Bundle;
import android.util.Log;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.Toast;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;

public class MainActivity extends AppCompatActivity {
    private static class LibraryLoader {
        // List of libraries to load
        private final String[] libraries = {
                "avutil",                // Base library for multimedia utilities
                "swresample",            // For audio resampling (depends on avutil)
                "avcodec",               // For multimedia decoding (depends on avutil)
                "avformat",              // For multimedia format handling (depends on avcodec)
                // QNN libraries
                "QnnSystem",             // Core system library for QNN
                "QnnGpu",                // GPU support for QNN
                "QnnDsp",                // DSP support for QNN
                "QnnDspV66Stub",
                "QnnHtp",
                "QnnHtpPrepare",
                "QnnHtpV68Stub",
                "QnnHtpV69Stub",
                "QnnHtpV73Stub",
                "QnnHtpV75Stub",
                "QnnHtpV79Stub",
                "QnnTFLiteDelegate",
                "tensorflowlite",
                "tensorflowlite_gpu_delegate",  // GPU support for TensorFlow Lite
                // Native WhisperKit libraries
                "whisperkit",
                "native-whisper",
                "app"
        };

        // Loads all the native libraries
        private void loadAllLibraries() {
            for (String lib : libraries) {
                try {
                    System.loadLibrary(lib);
                    System.out.println("Loaded library: lib" + lib + ".so");
                } catch (UnsatisfiedLinkError e) {
                    System.out.println("Failed to load library: lib" + lib + ".so");
                    e.printStackTrace();
                }
            }
        }
    }

    private MainViewModel mainViewModel;
    private String TAG = "ChatApp";
    private String htpExtConfigPath;

    private final ActivityResultLauncher<String[]> requestPermissionLauncher =
            registerForActivityResult(
                    new ActivityResultContracts.RequestMultiplePermissions(),
                    new ActivityResultCallback<Map<String, Boolean>>() {
                        @Override
                        public void onActivityResult(Map<String, Boolean> permissions) {
                            for (Map.Entry<String, Boolean> entry : permissions.entrySet()) {
                                String permission = entry.getKey();
                                Boolean isGranted = entry.getValue();
                                if (android.Manifest.permission.RECORD_AUDIO.equals(permission)) {
                                    if (isGranted) {
                                        Log.d(TAG, "Record permission is granted");
                                    } else {
                                        Log.d(TAG, "Record permission is not granted");
                                    }
                                } else if (android.Manifest.permission.WRITE_EXTERNAL_STORAGE.equals(permission)) {
                                    if (isGranted) {
                                        Log.d(TAG, "Write permission is granted");
                                    } else {
                                        Log.d(TAG, "Write permission is not granted");
                                    }
                                }
                            }
                        }
                    }
            );

    private void requestRecordPermission() {
        if (shouldShowRequestPermissionRationale(android.Manifest.permission.RECORD_AUDIO)) {
            // Optionally show rationale to the user explaining why the permission is needed
            Log.d(TAG, "Displaying permission rationale");
        } else {
            // Directly request the permission
            requestPermissionLauncher.launch(new String[]{android.Manifest.permission.RECORD_AUDIO});
        }
    }

    /**
     * copyAssetsDir: Copies provided assets to output path
     *
     * @param inputAssetRelPath relative path to asset from asset root
     * @param outputPath        output path to copy assets to
     * @throws IOException
     * @throws NullPointerException
     */
    void copyAssetsDir(String inputAssetRelPath, String outputPath) throws IOException, NullPointerException {
        File outputAssetPath = new File(Paths.get(outputPath, inputAssetRelPath).toString());

        String[] subAssetList = this.getAssets().list(inputAssetRelPath);
        if (subAssetList.length == 0) {
            // If file already present, skip copy.
            if (!outputAssetPath.exists()) {
                copyFile(inputAssetRelPath, outputAssetPath);
            }
            return;
        }

        // Input asset is a directory, create directory if not present already.
        if (!outputAssetPath.exists()) {
            outputAssetPath.mkdirs();
        }
        for (String subAssetName : subAssetList) {
            // Copy content of sub-directory
            String input_sub_asset_path = Paths.get(inputAssetRelPath, subAssetName).toString();
            // NOTE: Not to modify output path, relative asset path is being updated.
            copyAssetsDir(input_sub_asset_path, outputPath);
        }
    }

    /**
     * copyFile: Copies provided input file asset into output asset file
     *
     * @param inputFilePath   relative file path from asset root directory
     * @param outputAssetFile output file to copy input asset file into
     * @throws IOException
     */
    void copyFile(String inputFilePath, File outputAssetFile) throws IOException {
        InputStream in = this.getAssets().open(inputFilePath);
        OutputStream out = new FileOutputStream(outputAssetFile);

        byte[] buffer = new byte[1024 * 1024];
        int read;
        while ((read = in.read(buffer)) != -1) {
            out.write(buffer, 0, read);
        }
    }

    private Button loadLlamaButton;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Hide the action bar
        if (getSupportActionBar() != null) {
            getSupportActionBar().hide();
        }

        LibraryLoader libraryLoader = new LibraryLoader();
        libraryLoader.loadAllLibraries();

        try {
            // Get SoC model from build properties
            // As of now, only Snapdragon Gen 3 and 8 Elite is supported.
            HashMap<String, String> supportedSocModel = new HashMap<>();
            supportedSocModel.putIfAbsent("SM8750", "qualcomm-snapdragon-8-elite.json");
            supportedSocModel.putIfAbsent("SM8650", "qualcomm-snapdragon-8-gen3.json");
            supportedSocModel.putIfAbsent("QCS8550", "qualcomm-snapdragon-8-gen2.json");

            String socModel = android.os.Build.SOC_MODEL;
            if (!supportedSocModel.containsKey(socModel)) {
                String errorMsg = "Unsupported device. Please ensure you have one of the following device to run the ChatApp: " + supportedSocModel.toString();
                Log.e("ChatApp", errorMsg);
                Toast.makeText(this, errorMsg, Toast.LENGTH_LONG).show();
                finish();
            }

            // Copy assets to External cache
            //  - <assets>/models
            //      - has list of models with tokenizer.json, genie_config.json and model binaries
            //  - <assets>/htp_config/
            //      - has SM8750.json and SM8650.json and picked up according to device SOC Model at runtime.
            String externalDir = getExternalCacheDir().getAbsolutePath();
            try {
                // Copy assets to External cache if not already present
                copyAssetsDir("models", externalDir.toString());
                copyAssetsDir("htp_config", externalDir.toString());
            } catch (IOException e) {
                String errorMsg = "Error during copying model asset to external storage: " + e.toString();
                Log.e("ChatApp", errorMsg);
                Toast.makeText(this, errorMsg, Toast.LENGTH_SHORT).show();
                finish();
            }
            Path htpConfigPath = Paths.get(externalDir, "htp_config", supportedSocModel.get(socModel));
            htpExtConfigPath = htpConfigPath.toString();

            setContentView(R.layout.activity_main);
            
            // Set window soft input mode to adjust resize
            getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
            
            // Get the singleton instance of MainViewModel
            mainViewModel = MainViewModel.getInstance();
            mainViewModel.initialize(this);

            // Initialize UI components - only what's needed for MainActivity
            loadLlamaButton = findViewById(R.id.load_llama_button);
            
            setupModelLoadingButtons(htpExtConfigPath);
            requestRecordPermission();

        } catch (Exception e) {
            String errorMsg = "Unexpected error occurred while running ChatApp:" + e.toString();
            Log.e("ChatApp", errorMsg);
            Toast.makeText(this, errorMsg, Toast.LENGTH_LONG).show();
            finish();
        }
    }

    private void setupModelLoadingButtons(String htpConfigPath) {
        final String htpExtConfigPath = htpConfigPath;

        // Setup LLAMA model button to enable advanced chat
        loadLlamaButton.setOnClickListener(view -> {
            loadLlamaButton.setEnabled(false);
            loadLlamaButton.setText("Loading LLAMA...");

            mainViewModel.loadLlamaModel();

            // Observe LLAMA model state
            mainViewModel.getLlamaModelState().observe(this, state -> {
                switch (state) {
                    case LOADED:
                        loadLlamaButton.setText("LLAMA Loaded");
                        
                        // Launch the Conversation activity with required parameters
                        Intent intent = new Intent(MainActivity.this, Conversation.class);
                        intent.putExtra(Conversation.cConversationActivityKeyHtpConfig, htpExtConfigPath);
                        intent.putExtra(Conversation.cConversationActivityKeyModelName, "llama3_2_3b");
                        startActivity(intent);
                        break;
                    case ERROR:
                        loadLlamaButton.setText("Retry Loading LLAMA");
                        loadLlamaButton.setEnabled(true);
                        Toast.makeText(MainActivity.this, "Failed to load LLAMA model. Please try again.", Toast.LENGTH_LONG).show();
                        break;
                }
            });
        });
    }
}
