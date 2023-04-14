import java.io.*;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;


public class MyWebServer {
    private static final String SERVER_NAME = "MyWebServer";

    private static final ThreadLocal<SimpleDateFormat> DATE_FORMAT = ThreadLocal.withInitial(() -> new SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss z", Locale.US));

    public static void main(String[] args) {
        int port = Integer.parseInt(args[0]);
        String rootPath = args[1];

        ExecutorService executor = Executors.newCachedThreadPool();

        try (ServerSocket serverSocket = new ServerSocket(port)) {
            System.out.println("Listening on port " + port + "...");
            while (true) {
                final Socket socket = serverSocket.accept(); // Don't use try-with-resources here
                executor.submit(() -> {
                    try {
                        handleRequest(socket, rootPath);
                    } catch (IOException e) {
                        e.printStackTrace();
                    } finally {
                        try {
                            socket.close(); // Close the socket explicitly
                        } catch (IOException e) {
                            e.printStackTrace();
                        }
                    }
                });
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }


    private static void sendHeader(PrintWriter writer, HTTPResponse response) {
        writer.print(response.getStatusLine() + "\r\n");
        writer.print("Date: " + response.getDate() + "\r\n");
        writer.print("Server: " + response.getServer() + "\r\n");

        if (response.getLastModified() != null) {
            writer.print("Last-Modified: " + response.getLastModified() + "\r\n");
        }

        if (response.getContentLength() != null) {
            writer.print("Content-Length: " + response.getContentLength() + "\r\n");
        }

        if (response.getContentType() != null) {
            writer.print("Content-Type: " + response.getContentType() + "\r\n");
        }

        writer.print("\r\n");
        writer.flush();
    }

    private static void sendError(PrintWriter writer, int statusCode, String reasonPhrase, String message) {
        writer.print("HTTP/1.1 " + statusCode + " " + reasonPhrase + "\r\n");
        writer.print("Server: " + SERVER_NAME + "\r\n");
        writer.print("Date: " + DATE_FORMAT.get().format(new Date()) + "\r\n");
        writer.print("Content-Type: text/html\r\n");
        writer.print("Content-Length: " + message.length() + "\r\n");
        writer.print("\r\n");
        writer.print("<html><head><title>" + statusCode + " " + reasonPhrase + "</title></head><body>");
        writer.print("<h1>" + statusCode + " " + reasonPhrase + "</h1>");
        writer.print("<p>" + message + "</p>");
        writer.print("</body></html>");
        writer.flush();
    }

    private static String getContentType(String fileName) throws IOException {
        Path path = Paths.get(fileName);
        String contentType = Files.probeContentType(path);
        return contentType != null ? contentType : "application/octet-stream";
    }


    private static HTTPResponse createHTTPResponse(HTTPRequest request, String rootPath) throws IOException {
        HTTPResponse response = new HTTPResponse();

        File file = new File(rootPath, request.getPath());
        if (file.isDirectory()) {
            file = new File(file, "index.html");
        }

        if (!file.exists() || !file.getAbsolutePath().startsWith(new File(rootPath).getAbsolutePath())) {
            response.setStatusLine("HTTP/1.1 404 Not Found");
            response.setErrorMessage("File not found: " + file.getPath());
        } else {
            Date lastModified = new Date(file.lastModified());

            String ifModifiedSinceHeader = request.getIfModifiedSince();
            if (ifModifiedSinceHeader != null && !ifModifiedSinceHeader.isEmpty()) {
                try {
                    Date ifModifiedSinceDate = DATE_FORMAT.get().parse(ifModifiedSinceHeader);
                    if (ifModifiedSinceDate.getTime() >= lastModified.getTime()) {
                        // If the cached version is up-to-date, set the response to 304 Not Modified
                        response.setStatusLine("HTTP/1.1 304 Not Modified");
                    }
                } catch (ParseException e) {
                    // Set the response to 400 Bad Request if the "If-Modified-Since" header is not in the correct format
                    response.setStatusLine("HTTP/1.1 400 Bad Request");
                    response.setErrorMessage("Invalid 'If-Modified-Since' header format");
                }
            }


            if (response.getStatusLine() == null) {
                response.setStatusLine("HTTP/1.1 200 OK");
                response.setHeader("Content-Length", String.valueOf(file.length()));
                response.setHeader("Last-Modified", DATE_FORMAT.get().format(lastModified));

                String contentType = getContentType(file.getName());
                response.setHeader("Content-Type", contentType);

                byte[] content = Files.readAllBytes(file.toPath());
                response.setContent(content);
            }
        }

        response.setHeader("Date", DATE_FORMAT.get().format(new Date()));
        response.setHeader("Server", "MyWebServer");

        return response;
    }

    private static void handleRequest(Socket socket, String rootPath) throws IOException {
        try (
                BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream()));
                OutputStream out = socket.getOutputStream();
                PrintWriter writer = new PrintWriter(out)) {

            HTTPRequest request = HTTPRequest.parse(in);

            if (request.hasError()) {
                sendError(writer, request.getErrorCode(), request.getErrorReason(), request.getErrorMessage());
                return;
            }

            HTTPResponse response = createHTTPResponse(request, rootPath);

            if (request.getCommand().equals("HEAD")) {
                sendHeader(writer, response);
            } else if (request.getCommand().equals("GET")) {
                sendHeader(writer, response);
                out.write(response.getContent());
            }
        }
    }

    public static abstract class HTTPMessage {
        protected final HashMap<String, String> headers;

        public HTTPMessage() {
            headers = new HashMap<>();
        }

        public String getHeaderValue(String name) {
            return headers.get(name);
        }

        public void setHeader(String name, String value) {
            headers.put(name, value);
        }
    }

    public static class HTTPRequest extends HTTPMessage {
        private String command;
        private String path;
        private int errorCode;
        private String errorReason;
        private String errorMessage;

        private HTTPRequest() {
            super();
        }

        public static HTTPRequest parse(BufferedReader in) throws IOException {
            HTTPRequest request = new HTTPRequest();

            String line;
            line = in.readLine();

            String[] requestLineParts = line.split(" ");
            if (requestLineParts.length >= 2) {
                request.command = requestLineParts[0];
                request.path = requestLineParts[1];
            } else {
                request.errorCode = 400;
                request.errorReason = "Bad Request";
                request.errorMessage = "Invalid request line";
            }

            while ((line = in.readLine()) != null) {
                if (line.isEmpty()) {
                    break;
                }
                int colonIndex = line.indexOf(':');
                if (colonIndex != -1) {
                    String headerName = line.substring(0, colonIndex).trim();
                    String headerValue = line.substring(colonIndex + 1).trim();
                    request.setHeader(headerName, headerValue);
                }
            }

            return request;
        }

        public boolean hasError() {
            return errorCode != 0;
        }

        public int getErrorCode() {
            return errorCode;
        }

        public String getErrorReason() {
            return errorReason;
        }

        public String getErrorMessage() {
            return errorMessage;
        }

        public String getCommand() {
            return command;
        }

        public String getPath() {
            return path;
        }

        public String getIfModifiedSince() {
            return getHeaderValue("If-Modified-Since");
        }
    }

    public static class HTTPResponse extends HTTPMessage {
        private String statusLine;
        private byte[] content;
        private String errorMessage;

        public HTTPResponse() {
            super();
            statusLine = "HTTP/1.1 200 OK";
        }

        public String getStatusLine() {
            return statusLine;
        }

        public void setStatusLine(String statusLine) {
            this.statusLine = statusLine;
        }

        public byte[] getContent() {
            return content;
        }

        public void setContent(byte[] content) {
            this.content = content;
        }

        public void setErrorMessage(String errorMessage) {
            this.errorMessage = errorMessage;
        }

        public String getDate() {
            return getHeaderValue("Date");
        }

        public String getServer() {
            return getHeaderValue("Server");
        }

        public String getLastModified() {
            return getHeaderValue("Last-Modified");
        }

        public String getContentLength() {
            return getHeaderValue("Content-Length");
        }

        public String getContentType() {
            return getHeaderValue("Content-Type");
        }
    }
}private static void handleRequest(Socket socket, String rootPath) throws IOException {
        // Handle the incoming request for the client

        // Create the BufferedReader and PrintWriter from the input and output streams
        // Parse the request into the input stream
        try (
                BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
                OutputStream out = socket.getOutputStream();
                PrintWriter writer = new PrintWriter(new OutputStreamWriter(out, StandardCharsets.UTF_8))) {

            HTTPRequest request = HTTPRequest.parse(in);

            if (request.hasError()) {
                HTTPResponse errorResponse = new HTTPResponse();
                errorResponse.setStatusLine("HTTP/1.1 " + request.getErrorCode() + " " + request.getErrorReason());
                errorResponse.setErrorMessage(request.getErrorMessage());
                sendResponse(writer, errorResponse);
                return;
            }

            HTTPResponse response = createHTTPResponse(request, rootPath);

            if (response.getErrorMessage() != null) {
                sendResponse(writer, response);
                return;
            }

            if (request.getCommand().equals("HEAD")) {
                sendResponse(writer, response);
            } else if (request.getCommand().equals("GET")) {
                sendResponse(writer, response);
                out.write(response.getContent());
            }
        }
    }



    public static abstract class HTTPMessage {

        // Initialize the hashmap to store the header information
        protected final HashMap<String, String> headers;

        public HTTPMessage() {
            headers = new HashMap<>();
        }

        public String getHeaderValue(String name) {
            return headers.get(name);
        }

        public void setHeader(String name, String value) {
            headers.put(name, value);
        }
    }

    public static class HTTPRequest extends HTTPMessage {
        private String command;
        private String path;
        private String version;
        private int errorCode;
        private String errorReason;
        private String errorMessage;

        private HTTPRequest() {
            super();
        }

        public static HTTPRequest parse(BufferedReader in) throws IOException {
            HTTPRequest request = new HTTPRequest();

            String line;
            line = in.readLine();

            // Parse the request line and assign components
            String[] requestLineParts = line.split(" ");
            if (requestLineParts.length >= 3) {
                request.command = requestLineParts[0];
                request.path = requestLineParts[1];
                request.version = requestLineParts[2];
            } else {
                request.errorCode = 400;
                request.errorReason = "Bad Request";
                request.errorMessage = "Invalid request line";
            }

            // Parse the request header into name-value pairs and store in a hashmap
            while ((line = in.readLine()) != null) {
                if (line.isEmpty()) {
                    break;
                }
                int colonIndex = line.indexOf(':');
                if (colonIndex != -1) {
                    String headerName = line.substring(0, colonIndex).trim();
                    String headerValue = line.substring(colonIndex + 1).trim();
                    request.setHeader(headerName, headerValue);
                }
            }

            // Check for Host header in HTTP/1.1 requests
            if ("HTTP/1.1".equals(request.version) && request.getHeaderValue("Host") == null) {
                request.errorCode = 400;
                request.errorReason = "Bad Request";
                request.errorMessage = "Missing 'Host' header";
            }

            return request;
        }

        // Getter and setter methods
        public boolean hasError() {
            return errorCode != 0;
        }

        public int getErrorCode() {
            return errorCode;
        }

        public String getErrorReason() {
            return errorReason;
        }

        public String getErrorMessage() {
            return errorMessage;
        }

        public String getCommand() {
            return command;
        }

        public String getPath() {
            return path;
        }

        public String getIfModifiedSince() {
            return getHeaderValue("If-Modified-Since");
        }
    }

    public static class HTTPResponse extends HTTPMessage {
        // Supporting getter and setter methods to support handling classes
        private String statusLine;
        private byte[] content;
        private String errorMessage;

        public HTTPResponse() {
            super();
            statusLine = "HTTP/1.1 200 OK";
        }

        public String getStatusLine() {
            return statusLine;
        }

        public void setStatusLine(String statusLine) {
            this.statusLine = statusLine;
        }

        public byte[] getContent() {
            return content;
        }

        public void setContent(byte[] content) {
            this.content = content;
        }

        public void setErrorMessage(String errorMessage) {
            this.errorMessage = errorMessage;
        }

        public Map<String, String> getHeaders() {
            return headers;
        }

        public String getErrorMessage() {
            return errorMessage;
        }

    }
}