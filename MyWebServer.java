import java.io.*;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/*

Author: Keagan DeLong

Classes

    MyWebServer
        The main class responsible for starting the server and processing incoming requests

        Methods
            - main(String[] args): Starts the server with the specified command line arguments (port and root path)
            - handleRequest(Socket socket, String rootPath): Processes incoming requests and generates appropriate responses

    HTTPMessage (abstract)
        An abstract class representing a basic HTTP message with header manipulation methods

        Methods
            -getHeaderValue(String name): Returns the value of the specified header
            -setHeader(String name, String value): Sets the value of the specified header

    HTTPRequest
        A class representing an HTTP request, extending the HTTPMessage abstract class

        Methods
            - parse(BufferedReader in): Parses an HTTP request from the provided BufferedReader
            - hasError(): Returns true if the request contains an error, false otherwise
            - getErrorCode(): Returns the error code of the request
            - getErrorReason(): Returns the error reason of the request
            - getErrorMessage(): Returns the error message of the request
            - getCommand(): Returns the HTTP command (e.g., GET, POST, etc.) of the request
            - getPath(): Returns the requested path of the request
            - getIfModifiedSince(): Returns the value of the "If-Modified-Since" header

    HTTPResponse
        A class representing an HTTP response, extending the HTTPMessage abstract class

        Methods
            - getStatusLine(): Returns the status line of the response
            - setStatusLine(String statusLine): Sets the status line of the response
            - getContent(): Returns the content of the response as a byte array
            - setContent(byte[] content): Sets the content of the response using a byte array
            - setErrorMessage(String errorMessage): Sets the error message of the response
            - getHeaders(): Returns a map of header names and values
            - getErrorMessage(): Returns the error message of the response

Additional Methods
    - sendResponse(PrintWriter writer, HTTPResponse response): Sends the response to the client
    - getContentType(String fileName): Returns the content type of the specified file
    - createHTTPResponse(HTTPRequest request, String rootPath): Creates an HTTP response based on the given request and server root path

Features
    - Multi-threaded server: The server can handle multiple client connections simultaneously using the ExecutorService with a cached thread pool
    - Supports HTTP/1.1: The server processes HTTP/1.1 requests and requires the "Host" header for such requests
    - HTTP methods: The server supports "GET" and "HEAD" methods. Other methods will result in a "501 Not Implemented" response
    - Conditional GET: The server supports the "If-Modified-Since" header, allowing clients to request content only if it has been modified since a certain date
    - MIME types: The server automatically detects and sets the "Content-Type" header based on the file extension
 */


public class MyWebServer {
    private static final int MIN_PORT = 1;
    private static final int MAX_PORT = 65535;

    private static final ThreadLocal<SimpleDateFormat> DATE_FORMAT = ThreadLocal.withInitial(() -> new SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss z", Locale.US));

    public static void main(String[] args) {
        // Check that exactly two arguments were provided
        if (args.length != 2) {
            System.out.println("Usage: java MyWebServer <port> <rootPath>");
            System.exit(1);
        }

        // Parse the port number and check that it falls within the valid range of numbers
        int port;
        try {
            port = Integer.parseInt(args[0]);
            if (port < MIN_PORT || port > MAX_PORT) {
                throw new NumberFormatException();
            }
        } catch (NumberFormatException e) {
            System.out.println("Invalid port number. Port must be between " + MIN_PORT + " and " + MAX_PORT);
            System.exit(1);
            return;
        }

        String rootPath = args[1];

        // Create new thread to handle incoming requests
        ExecutorService executor = Executors.newCachedThreadPool();


        // Enter a loop to listen for incoming connections
        // When a connection is accepted create a new task in the thread pool
        // When the socket is accepted pass the request to the handler function
        try (ServerSocket serverSocket = new ServerSocket(port)) {
            System.out.println("Listening on port " + port + "...");
            while (true) {
                final Socket socket = serverSocket.accept();
                executor.submit(() -> {
                    try {
                        handleRequest(socket, rootPath);
                    } catch (IOException e) {
                        e.printStackTrace();
                    } finally {
                        try {
                            socket.close();
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

    private static void sendResponse(PrintWriter writer, HTTPResponse response) {
        // Print the status line of the HTTP response to the writer
        // If the response has an error message create an HTML body with the error message and add it to the writer
        writer.print(response.getStatusLine() + "\r\n");

        for (Entry<String, String> header : response.getHeaders().entrySet()) {
            writer.print(header.getKey() + ": " + header.getValue() + "\r\n");
        }

        writer.print("\r\n");

        if (response.getErrorMessage() != null) {
            String errorContent = "<html><head><title>" + response.getStatusLine() + "</title></head><body>" +
                    "<h1>" + response.getStatusLine() + "</h1>" +
                    "<p>" + response.getErrorMessage() + "</p>" +
                    "</body></html>";
            writer.print(errorContent);
        }

        writer.flush();
    }


    private static String getContentType(String fileName) throws IOException {
        // Take a file name as input and returns the MIME type of the file
        Path path = Paths.get(fileName);
        String contentType = Files.probeContentType(path);
        return contentType != null ? contentType : "application/octet-stream";
    }

    private static HTTPResponse createHTTPResponse(HTTPRequest request, String rootPath) throws IOException {
        // Take an HTTP request and the root path of the web server as input, and returns a response

        // Create a new response object
        HTTPResponse response = new HTTPResponse();

        // If the file does not exist or is not a regular file  method set the response status to 404
        File file = new File(rootPath, request.getPath()).getCanonicalFile();
        if (!file.exists() || !file.isFile() || !file.getAbsolutePath().startsWith(new File(rootPath).getCanonicalPath())) {
            response.setStatusLine("HTTP/1.1 404 Not Found");
            response.setErrorMessage("File not found: " + file.getPath());

        }
        // Otherwise check the "If-Modified-Since" header and return the appropriate header
        else {
            Date lastModified = new Date(file.lastModified());

            String ifModifiedSinceHeader = request.getIfModifiedSince();
            boolean validIfModifiedSince = true;
            if (ifModifiedSinceHeader != null && !ifModifiedSinceHeader.isEmpty()) {
                try {
                    Date ifModifiedSinceDate = DATE_FORMAT.get().parse(ifModifiedSinceHeader);
                    if (ifModifiedSinceDate.getTime() / 1000 >= lastModified.getTime() / 1000) {
                        // If the cached version is up-to-date, set the response to 304 Not Modified
                        response.setStatusLine("HTTP/1.1 304 Not Modified");
                        validIfModifiedSince = false;
                    }
                } catch (ParseException e) {
                    // Set the response to 400 Bad Request if the "If-Modified-Since" header is not in the correct format
                    response.setStatusLine("HTTP/1.1 400 Bad Request");
                    response.setErrorMessage("Invalid 'If-Modified-Since' header format");
                    validIfModifiedSince = false;
                }
            }

            // Set the response to 200 if no other code was found,
            // Set the content length, last modified date, and content type headers, and read the file contents into a byte array
            if (validIfModifiedSince) {
                response.setStatusLine("HTTP/1.1 200 OK");
                response.setHeader("Content-Length", String.valueOf(file.length()));
                response.setHeader("Last-Modified", DATE_FORMAT.get().format(lastModified));

                String contentType = getContentType(file.getName());
                response.setHeader("Content-Type", contentType);

                byte[] content = Files.readAllBytes(file.toPath());
                response.setContent(content);
            }
        }

        // Add the "Date" and "Server" headers to the response
        response.setHeader("Date", DATE_FORMAT.get().format(new Date()));
        response.setHeader("Server", "MyWebServer");

        // Add the "Unsupported HTTP methods" check
        if (!request.getCommand().equals("GET") && !request.getCommand().equals("HEAD")) {
            response.setStatusLine("HTTP/1.1 501 Not Implemented");
            response.setErrorMessage("Unsupported HTTP method: " + request.getCommand());
        }

        return response;
    }


    private static void handleRequest(Socket socket, String rootPath) throws IOException {
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