package com.drakasmit.youtubemp3;

import android.app.Activity;
import android.os.Bundle;
import android.os.Environment;
import android.view.Gravity;
import android.widget.*;
import java.io.File;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import dev.ffmpegkit_maintained.ytdlp.YtDlp;
import dev.ffmpegkit_maintained.ytdlp.YtDlpRequest;
import dev.ffmpegkit_maintained.ytdlp.YtDlpResponse;

public class MainActivity extends Activity {
    private EditText urlInput;
    private Spinner qualitySpinner;
    private CheckBox archiveCheck;
    private Button startButton;
    private TextView logView;
    private volatile boolean engineReady = false;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        buildUi();
        startButton.setEnabled(false);
        append("Инициализация движка загрузки...");
        executor.execute(() -> {
            try {
                YtDlp.init(getApplicationContext());
                engineReady = true;
                append("Движок загрузки готов.");
                runOnUiThread(() -> startButton.setEnabled(true));
            } catch (Exception e) {
                append("Ошибка инициализации: " + e.getMessage());
            }
        });
    }

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(24, 28, 24, 20);
        root.setBackgroundColor(0xFFF5F5F5);

        TextView title = new TextView(this);
        title.setText("YouTube → MP3");
        title.setTextSize(26);
        title.setTextColor(0xFF171717);
        title.setGravity(Gravity.CENTER);
        root.addView(title, new LinearLayout.LayoutParams(-1, -2));

        TextView hint = new TextView(this);
        hint.setText("Ссылка на видео или плейлист, который ты вправе сохранять");
        hint.setTextSize(14);
        hint.setPadding(0, 24, 0, 8);
        root.addView(hint);

        urlInput = new EditText(this);
        urlInput.setSingleLine(true);
        urlInput.setHint("https://www.youtube.com/…");
        root.addView(urlInput, new LinearLayout.LayoutParams(-1, -2));

        Button paste = new Button(this);
        paste.setText("Вставить из буфера");
        paste.setOnClickListener(v -> {
            android.content.ClipboardManager cm =
                (android.content.ClipboardManager)getSystemService(CLIPBOARD_SERVICE);
            if (cm != null && cm.hasPrimaryClip() && cm.getPrimaryClip().getItemCount() > 0) {
                CharSequence value = cm.getPrimaryClip().getItemAt(0).coerceToText(this);
                if (value != null) urlInput.setText(value.toString());
            }
        });
        root.addView(paste);

        TextView qualityLabel = new TextView(this);
        qualityLabel.setText("Качество MP3");
        qualityLabel.setPadding(0, 18, 0, 4);
        root.addView(qualityLabel);

        qualitySpinner = new Spinner(this);
        String[] qualities = {"Лучшее доступное", "128K", "192K", "256K", "320K"};
        qualitySpinner.setAdapter(new ArrayAdapter<>(this,
            android.R.layout.simple_spinner_dropdown_item, qualities));
        root.addView(qualitySpinner);

        archiveCheck = new CheckBox(this);
        archiveCheck.setText("Пропускать уже скачанные видео");
        archiveCheck.setChecked(true);
        root.addView(archiveCheck);

        startButton = new Button(this);
        startButton.setText("Скачать MP3");
        startButton.setOnClickListener(v -> startDownload());
        root.addView(startButton);

        TextView folder = new TextView(this);
        folder.setText("Сохранение: Music/YouTube_MP3 (папка приложения)");
        folder.setTextSize(12);
        folder.setPadding(0, 8, 0, 12);
        root.addView(folder);

        logView = new TextView(this);
        logView.setTextSize(12);
        logView.setTextColor(0xFF222222);
        ScrollView scroll = new ScrollView(this);
        scroll.addView(logView);
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));

        setContentView(root);
    }

    private void startDownload() {
        String url = urlInput.getText().toString().trim();
        if (url.isEmpty()) {
            Toast.makeText(this, "Вставь ссылку.", Toast.LENGTH_SHORT).show();
            return;
        }
        if (!(url.startsWith("https://") || url.startsWith("http://"))) {
            Toast.makeText(this, "Проверь ссылку.", Toast.LENGTH_SHORT).show();
            return;
        }
        if (!engineReady) {
            Toast.makeText(this, "Движок ещё не готов.", Toast.LENGTH_SHORT).show();
            return;
        }

        final String quality = new String[]{"0", "128K", "192K", "256K", "320K"}[
            qualitySpinner.getSelectedItemPosition()];
        final boolean useArchive = archiveCheck.isChecked();
        startButton.setEnabled(false);
        append("\nЗапуск: " + url);

        executor.execute(() -> {
            try {
                File music = getExternalFilesDir(Environment.DIRECTORY_MUSIC);
                if (music == null) throw new IllegalStateException("Папка Music недоступна");
                File output = new File(music, "YouTube_MP3");
                if (!output.exists() && !output.mkdirs())
                    throw new IllegalStateException("Не удалось создать папку загрузок");

                YtDlpRequest request = new YtDlpRequest(url)
                    .setOutputTemplate(new File(output, "%(title)s.%(ext)s").getAbsolutePath())
                    .addOption("--yes-playlist")
                    .addOption("--ignore-errors")
                    .addOption("--no-overwrites")
                    .addOption("-x")
                    .addOption("--audio-format", "mp3")
                    .addOption("--audio-quality", quality)
                    .addOption("--newline");

                if (useArchive) {
                    request.addOption("--download-archive",
                        new File(output, "downloaded.txt").getAbsolutePath());
                }

                Future<YtDlpResponse> task = YtDlp.executeAsync(request, (progress, eta, line) -> {
                    if (line != null && !line.isEmpty()) {
                        append(String.format(java.util.Locale.US, "%.1f%% | ETA %ds | %s",
                            progress, eta, line));
                    }
                });
                YtDlpResponse result = task.get();
                if (result.isSuccess()) {
                    append("Готово. Загрузка завершена.");
                } else {
                    append("Загрузка завершилась с кодом ошибки: " + result.getExitCode());
                }
            } catch (Exception e) {
                append("Ошибка загрузки: " + e.getMessage());
            } finally {
                runOnUiThread(() -> startButton.setEnabled(engineReady));
            }
        });
    }

    private void append(String line) {
        runOnUiThread(() -> {
            if (logView != null) logView.append(line + "\n");
        });
    }

    @Override protected void onDestroy() {
        executor.shutdown();
        super.onDestroy();
    }
}
