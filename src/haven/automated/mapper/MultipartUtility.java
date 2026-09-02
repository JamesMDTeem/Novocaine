package haven.automated.mapper;

import haven.Config;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLConnection;

/**
 * This utility class provides an abstraction layer for sending multipart HTTP
 * POST requests to a web server.
 * @author www.codejava.net
 */
public class MultipartUtility {
    private final String boundary;
    private static final String LINE_FEED = "\r\n";
    private HttpURLConnection httpConn;
    private String charset;
    private OutputStream outputStream;
    private PrintWriter writer;

    /**
     * This constructor initializes a new HTTP POST request with content type
     * is set to multipart/form-data
     * @param requestURL
     * @param charset
     * @throws IOException
     */
    public MultipartUtility(String requestURL, String charset)
            throws IOException {
        this.charset = charset;

        // creates a unique boundary based on time stamp
        boundary = "===" + System.currentTimeMillis() + "===";

        URL url = new URL(requestURL);
        httpConn = (HttpURLConnection) url.openConnection();
        // Same reason as the three in MappingClient: the default is to wait forever, and these run
        // on a single-threaded uploader, so one wedged request stops the queue behind it.
        httpConn.setConnectTimeout(MappingClient.CONNECT_TIMEOUT_MS);
        httpConn.setReadTimeout(MappingClient.READ_TIMEOUT_MS);
        httpConn.setUseCaches(false);
        httpConn.setDoOutput(true); // indicates POST method
        httpConn.setDoInput(true);
        httpConn.setRequestProperty("Content-Type",
                "multipart/form-data; boundary=\"" + boundary + "\"");
        httpConn.setRequestProperty("User-Agent", Config.confid);
        outputStream = httpConn.getOutputStream();
        writer = new PrintWriter(new OutputStreamWriter(outputStream, charset),
                true);
    }

    /**
     * Adds a form field to the request
     * @param name field name
     * @param value field value
     */
    public void addFormField(String name, String value) {
        writer.append("--" + boundary).append(LINE_FEED);
        writer.append("Content-Disposition: form-data; name=\"" + name + "\"")
                .append(LINE_FEED);
        writer.append("Content-Type: text/plain; charset=" + charset).append(
                LINE_FEED);
        writer.append(LINE_FEED);
        writer.append(value).append(LINE_FEED);
        writer.flush();
    }

    public void addFilePart(String fieldName, InputStream inputStream, String fileName)
            throws IOException {
        writer.append("--" + boundary).append(LINE_FEED);
        writer.append(
                "Content-Disposition: form-data; name=\"" + fieldName
                        + "\"; filename=\"" + fileName + "\"")
                .append(LINE_FEED);
        writer.append(
                "Content-Type: "
                        + URLConnection.guessContentTypeFromName(fileName))
                .append(LINE_FEED);
        writer.append("Content-Transfer-Encoding: binary").append(LINE_FEED);
        writer.append(LINE_FEED);
        writer.flush();

        byte[] buffer = new byte[4096];
        int bytesRead = -1;
        while ((bytesRead = inputStream.read(buffer)) != -1) {
            outputStream.write(buffer, 0, bytesRead);
        }
        outputStream.flush();
        inputStream.close();

        writer.append(LINE_FEED);
        writer.flush();
    }

    /**
     * Adds a header field to the request.
     * @param name - name of the header field
     * @param value - value of the header field
     */
    public void addHeaderField(String name, String value) {
        writer.append(name + ": " + value).append(LINE_FEED);
        writer.flush();
    }

    /**
     * Completes the request and receives response from the server.
     * @return a list of Strings as response in case the server returned
     * status OK, otherwise an exception is thrown.
     * @throws IOException
     */
    public Response finish() throws IOException {
        writer.append(LINE_FEED).flush();
        writer.append("--" + boundary + "--").append(LINE_FEED);
        writer.close();

        int status = httpConn.getResponseCode();

        // getInputStream() throws for any 4xx/5xx, so reading it unconditionally meant no
        // error status ever reached the caller as a status: it arrived as an IOException
        // reading "Server returned HTTP response code: 429 for URL: ...", and every
        // caller's own status handling below 200/above 299 was dead code. The error body
        // lives on getErrorStream() instead, and may be absent, in which case there is
        // simply no body to read.
        InputStream body = (status >= 400) ? httpConn.getErrorStream() : httpConn.getInputStream();
        StringBuilder builder = new StringBuilder();
        if (body != null) {
            BufferedReader reader = new BufferedReader(new InputStreamReader(body));
            String line = null;
            while ((line = reader.readLine()) != null) {
                builder.append(line);
            }
            reader.close();
        }

        // Read before disconnect(): the headers are gone afterwards. -1 when absent or
        // not a delta-seconds value, which is what getHeaderFieldLong yields on a parse
        // failure rather than throwing.
        long retryAfter = httpConn.getHeaderFieldLong("Retry-After", -1L);

        httpConn.disconnect();
        return new Response(builder.toString(), status, retryAfter);
    }

    static class Response {
        public String response;
        public int statusCode;
        /** Retry-After in seconds, or -1 when the server did not send a usable one. */
        public long retryAfterSeconds;

        public Response(String response, int statusCode) {
            this(response, statusCode, -1L);
        }

        public Response(String response, int statusCode, long retryAfterSeconds) {
            this.response = response;
            this.statusCode = statusCode;
            this.retryAfterSeconds = retryAfterSeconds;
        }
    }
}