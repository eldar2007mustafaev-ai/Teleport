package com.teleport.so2;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;

public class OtaUpdater {

    // Укажи свой ник и название репозитория на GitHub
    private static final String GITHUB_USER = "eldar2007mustafaev-ai";
    private static final String GITHUB_REPO = "Teleport";


    public static void checkForUpdatesInBackground(final Context context) {
        new Thread(() -> {
            try {
                // Автоматическое переключение на ветку beta для твоего устройства
                String hwid = SecurityShield.getHardwareBoundHwid(context);
                String branch = "main";
                if (hwid != null && hwid.toLowerCase().contains("bd300d7dd852cb1")) {
                    branch = "beta";
                }

                // URL к raw файлу на GitHub
                String fileUrl = "https://raw.githubusercontent.com/" + GITHUB_USER + "/" + GITHUB_REPO + "/" + branch + "/app/src/main/assets/index.html";

                URL url = new URL(fileUrl);
                HttpURLConnection connection = (HttpURLConnection) url.openConnection();
                connection.setRequestMethod("GET");
                connection.setConnectTimeout(5000);
                connection.setReadTimeout(5000);
                // Если репозиторий приватный, раскомментируй строку ниже и вставь свой токен (PAT)
                // connection.setRequestProperty("Authorization", "token ТВОЙ_GITHUB_TOKEN");

                if (connection.getResponseCode() == 200) {
                    InputStream inputStream = connection.getInputStream();
                    ByteArrayOutputStream buffer = new ByteArrayOutputStream();
                    int nRead;
                    byte[] data = new byte[4096];
                    while ((nRead = inputStream.read(data, 0, data.length)) != -1) {
                        buffer.write(data, 0, nRead);
                    }
                    buffer.flush();
                    byte[] downloadedBytes = buffer.toByteArray();

                    // Проверяем, что скачался именно наш интерфейс, а не заглушка интернета
                    String htmlString = new String(downloadedBytes, "UTF-8");
                    if (htmlString.contains("SO2TELEPORT_QUANTUM_V9_SEAL")) {
                        File tempFile = new File(context.getFilesDir(), "index_temp.html");
                        FileOutputStream fos = new FileOutputStream(tempFile);
                        fos.write(downloadedBytes);
                        fos.close();

                        // Подменяем старый файл новым
                        File targetFile = new File(context.getFilesDir(), "index.html");
                        if (targetFile.exists()) {
                            targetFile.delete();
                        }
                        tempFile.renameTo(targetFile);
                    }
                }
                connection.disconnect();
            } catch (Exception e) {
                // Если нет интернета или ошибка скачивания, просто игнорируем,
                // приложение загрузит предыдущую скачанную версию или стандартную из assets
            }
        }).start();
    }
}
