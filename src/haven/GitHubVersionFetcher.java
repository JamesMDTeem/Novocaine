package haven;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.*;

public class GitHubVersionFetcher {
    private static final ExecutorService executor = Executors.newCachedThreadPool();

    /**
     * Asks GitHub for a repository's latest release tag and hands it to the callback.
     *
     * The callback runs on the worker thread, not the caller's. This used to submit the task
     * and then immediately future.get() on the calling thread - which is the UI thread building
     * the login screen - so the "async" executor bought nothing and the client sat frozen for
     * as long as the request took. With no timeouts set on the connection either (see below),
     * a network that accepts the connection and then goes quiet would hang the login screen
     * indefinitely.
     */
    public static void fetchLatestVersion(String owner, String repo, VersionCallback callback) {
        // Set loading state
        callback.onVersionFetched("Loading...");

        executor.submit(() -> {
            try {
                callback.onVersionFetched(getLatestReleaseVersion(owner, repo));
            } catch (Exception e) {
                callback.onVersionFetched("Failed");
            }
        });
    }

    private static String getLatestReleaseVersion(String owner, String repo) throws Exception {
        String urlString = String.format("https://api.github.com/repos/%s/%s/releases/latest", owner, repo);
        HttpURLConnection connection = null;
        BufferedReader br = null;

        try {
            URL url = new URL(urlString);
            connection = (HttpURLConnection) url.openConnection();
            connection.setRequestMethod("GET");
            connection.setRequestProperty("Accept", "application/vnd.github.v3+json");
            // HttpURLConnection defaults to waiting forever on both.
            connection.setConnectTimeout(10000);
            connection.setReadTimeout(15000);

            if (connection.getResponseCode() != 200) {
                throw new RuntimeException("Failed : HTTP error code : " + connection.getResponseCode());
            }

            br = new BufferedReader(new InputStreamReader(connection.getInputStream()));
            StringBuilder response = new StringBuilder();
            String output;

            while ((output = br.readLine()) != null) {
                response.append(output);
            }

            return parseTagName(response.toString()); // Pass the entire response to parse
        } finally {
            if (br != null) {
                br.close(); // Close BufferedReader
            }
            if (connection != null) {
                connection.disconnect(); // Close the connection
            }
        }
    }

    private static String parseTagName(String jsonResponse) {
        String tagNameKey = "\"tag_name\":";
        int startIndex = jsonResponse.indexOf(tagNameKey) + tagNameKey.length();
        int endIndex = jsonResponse.indexOf("\"", startIndex + 1);
        return jsonResponse.substring(startIndex + 1, endIndex); // Extract the version string
    }

    // Define the callback interface as a nested interface
    public interface VersionCallback {
        void onVersionFetched(String version);
    }
}
