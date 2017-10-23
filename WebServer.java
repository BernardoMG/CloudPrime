import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.util.HashMap;
import java.util.Map;
import java.util.*;
import java.io.*;
import java.nio.file.*;
import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

public class WebServer {
	
	
	  private static final int HTTP_OK_STATUS = 200;	
	  private static final int LOCALHOST_ADDRESS = 8000;
	  private static final int NUMBER_OF_THREADS = 20;

    public static void main(String[] args) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress(LOCALHOST_ADDRESS), 0);
        server.createContext("/cloudprime", new HtmlHandler());
        server.createContext("/f.html", new FactorizationHandler());
        server.setExecutor(java.util.concurrent.Executors.newFixedThreadPool(NUMBER_OF_THREADS)); // creates a default executor
    }
    
    static class FactorizationHandler implements HttpHandler {	  
	    public void handle(HttpExchange t) throws IOException { 
	    	
		     String[] numberToFactorize = new String[5];	   	
		     String requestMethod = t.getRequestMethod();	
		     if (requestMethod.equalsIgnoreCase("GET")) {
		    	 String requestQuery = t.getRequestURI().getQuery();
		    	 Map<String, String> params = queryToMap(requestQuery); 
		    	 numberToFactorize[0] = params.get("n");
		    	 numberToFactorize[1] = String.valueOf(Thread.currentThread().getId());
		     }
	    	Process p = Runtime.getRuntime().exec(new String[] {"java" ,"-cp", "/home/ec2-user/","-XX:-UseSplitVerifier", "IntFactorization", numberToFactorize[0], numberToFactorize[1]});

			try{
			 	p.waitFor();
			}catch(InterruptedException e){
				e.printStackTrace();
			}
			Headers h = t.getResponseHeaders();
			h.add("Content-type", "text/html");
			 

			File file = new File("results"+numberToFactorize[0]+".txt");
			 
			byte[] byteArray = new byte[(int)file.length()];
			FileInputStream fileStream = new FileInputStream(file);
			BufferedInputStream buffer = new BufferedInputStream(fileStream);
			buffer.read(byteArray, 0, byteArray.length);
			
		    t.sendResponseHeaders(HTTP_OK_STATUS, file.length());
			OutputStream os = t.getResponseBody();
			os.write(byteArray, 0, byteArray.length);
			os.close();

	    	String [] args = {"Metrics"+numberToFactorize[0]+".txt"};
            try{
                FactDynamoDB.main(args);
            }catch(Exception e){
                e.printStackTrace();
            }
		}
  	}

    static class HtmlHandler implements HttpHandler {
        public void handle(HttpExchange t) throws IOException {
        	
        	StringBuilder response = new StringBuilder();		     
        	response.append("<html><head><meta http-equiv='Content-Type' content='text/html; charset=UTF-8'> <title>CloudPrime Form</title></head>");
        	response.append("<body><h1>CloudPrime</h1><form  id='htmlForm' action='/f.html' method='get'> <p>Semiprime Number: <input type='text' name='n'/></p>");
        	response.append("<div class=\"button\"><button type=\"submit\">Submit</button></div></form></body></html>");
        	
        	t.sendResponseHeaders(HTTP_OK_STATUS, response.length());
        	OutputStream os = t.getResponseBody();
        	os.write(response.toString().getBytes());
    		os.close();
        }
    }
    
    static public Map<String, String> queryToMap(String query){
    	
	    Map<String, String> result = new HashMap<String, String>();
	    for (String param : query.split("&")) {
	        String pair[] = param.split("=");
	        if (pair.length>1) {
	            result.put(pair[0], pair[1]);
	        }else{
	            result.put(pair[0], "");
	        }
	    }
	    return result;
	}
}
