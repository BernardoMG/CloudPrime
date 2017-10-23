/*
 * Response Timeout    = 
 * HealthCheckInterval = 30 seconds
 * UnhealthyThreshold  = 2  seconds 
 * HealthyThreshold    = 2  seconds
 */

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.ConnectException;
import java.net.InetSocketAddress;
import java.net.URL;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.LinkedBlockingQueue;

import javafx.util.Pair;

import com.amazonaws.AmazonClientException;
import com.amazonaws.AmazonServiceException;
import com.amazonaws.auth.AWSCredentials;
import com.amazonaws.auth.profile.ProfileCredentialsProvider;
import com.amazonaws.regions.Region;
import com.amazonaws.regions.Regions;
import com.amazonaws.services.cloudwatch.AmazonCloudWatchClient;
import com.amazonaws.services.cloudwatch.model.Datapoint;
import com.amazonaws.services.cloudwatch.model.Dimension;
import com.amazonaws.services.cloudwatch.model.GetMetricStatisticsRequest;
import com.amazonaws.services.cloudwatch.model.GetMetricStatisticsResult;
import com.amazonaws.services.dynamodbv2.AmazonDynamoDBClient;
import com.amazonaws.services.dynamodbv2.model.AttributeValue;
import com.amazonaws.services.dynamodbv2.model.ComparisonOperator;
import com.amazonaws.services.dynamodbv2.model.Condition;
import com.amazonaws.services.dynamodbv2.model.ScanRequest;
import com.amazonaws.services.dynamodbv2.model.ScanResult;
import com.amazonaws.services.ec2.AmazonEC2;
import com.amazonaws.services.ec2.AmazonEC2Client;
import com.amazonaws.services.ec2.model.DescribeInstancesResult;
import com.amazonaws.services.ec2.model.Instance;
import com.amazonaws.services.ec2.model.Reservation;
import com.amazonaws.services.ec2.model.RunInstancesRequest;
import com.amazonaws.services.ec2.model.RunInstancesResult;
import com.amazonaws.services.ec2.model.TerminateInstancesRequest;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;


public class LoadBalancer{
	
	private static final String INSTANCE_AMI = "ami-15f60a75";
	
	private static final int HTTP_OK_STATUS = 200;	
	private static final int LOCALHOST_ADDRESS = 8080;
	private static final int NUMBER_OF_THREADS = 20;
	private static final int MIN_INSTANCES_DESIRED = 1;
	private static final int MAX_INSTANCES_DESIRED = 20;
	private static final String OPERATIONAL = "InService";
	private static final String OUTOFSERVICE = "OutOfService";
	private static Map<String,Instance> runningInstances;
	private static Set<String> instancesDNS;
	private static Set<Instance> instances;
	private static List<Reservation> reservations;
	private static DescribeInstancesResult describeInstancesRequest;

	private static long freshInstanceLaunchTime; 

	private static double lastCpuAverageState=0;
	private static boolean autoScalarRefreshTime = false;
	
	//String => ID da instancia ; Datapoint => último datapoint para a instancia com o dns anterior
	private static Map<String,Datapoint> lastStatePerInstance = new HashMap<String,Datapoint>();
	
	static AmazonEC2 ec2;
	static AmazonCloudWatchClient cloudWatch; 
	static AmazonDynamoDBClient dynamoDB;
	
	private static ConcurrentMap<String,List<FactorizationRequest>> requestsBucketPerInstance = new ConcurrentHashMap<String, List<FactorizationRequest>>();

	// < IdInstance , healthStatus >  ; healthStatus may be : InService or OutOfService 
	private static ConcurrentMap<String,HealthValuesPair> healthStatusPerInstance = new ConcurrentHashMap<String, HealthValuesPair>();
	private static BlockingQueue<FactorizationRequest> requestsBucket = new LinkedBlockingQueue<FactorizationRequest>();	

	private static void init() throws Exception {
		
        /*
         * The ProfileCredentialsProvider will return your [default]
         * credential profile by reading from the credentials file located at
         * (~/.aws/credentials).
         */
        AWSCredentials credentials = null; 
        try {
            credentials = new ProfileCredentialsProvider().getCredentials();
        } catch (Exception e) {
            throw new AmazonClientException(
                    "Cannot load the credentials from the credential profiles file. " +
                    "Please make sure that your credentials file is at the correct " +
                    "location (~/.aws/credentials), and is in valid format.",
                    e);
        }
        
        ec2 = new AmazonEC2Client(credentials);
        cloudWatch= new AmazonCloudWatchClient(credentials);
        dynamoDB = new AmazonDynamoDBClient(credentials);
        
        Region usWest2 = Region.getRegion(Regions.US_WEST_2);
        dynamoDB.setRegion(usWest2);
        
        HttpServer server = HttpServer.create(new InetSocketAddress(LOCALHOST_ADDRESS), 0);
        server.createContext("/cloudprime", new HtmlHandler());
        server.createContext("/lb", new LBHandler());
        server.setExecutor(java.util.concurrent.Executors.newFixedThreadPool(NUMBER_OF_THREADS)); // creates a default executor AQUI N E PRECISO ALGO RELACIONADO COM THREADS??
        server.start();
    }

	private static class HtmlHandler implements HttpHandler {
        public void handle(HttpExchange t) throws IOException {
        	StringBuilder response = new StringBuilder();		     
        	response.append("<html><head><meta http-equiv='Content-Type' content='text/html; charset=UTF-8'> <title>CloudPrime Form</title></head>");
        	response.append("<body><h1>CloudPrime</h1><form  id='htmlForm' action='/lb' method='get'> <p>Semiprime Number: <input type='text' name='n'/></p>");
        	response.append("<div class=\"button\"><button type=\"submit\">Submit</button></div></form></body></html>");
        	
        	t.sendResponseHeaders(HTTP_OK_STATUS, response.length());
        	OutputStream os = t.getResponseBody();
        	os.write(response.toString().getBytes());
    		os.close();
        }
    }
	
	static class LBHandler implements HttpHandler {	  
	    public void handle(HttpExchange t) throws IOException { 
	    	
	    	String tableName = "Metrics";
	    	
	    	String requestMethod = t.getRequestMethod();	
	    	if (requestMethod.equalsIgnoreCase("GET")) {
				String requestQuery = t.getRequestURI().getQuery();
				Map<String, String> params = queryToMap(requestQuery);
				String numToFactorize = params.get("n");
			 
				System.out.println("Request Number: "+ numToFactorize);
			 
				String requestConfirmation = "Your request was delivered";
				t.sendResponseHeaders(HTTP_OK_STATUS, requestConfirmation.length());
				
		     	OutputStream newOs = t.getResponseBody();
		     	newOs.write(requestConfirmation.toString().getBytes());
		 		newOs.close();
				
				//executeRequest(numToFactorize);
		 		
		 		//Example of a query on DynamoDB
	            // Scan items for movies with a year attribute greater than 1985
	            HashMap<String, Condition> scanFilter = new HashMap<String, Condition>();
	            Condition condition = new Condition()
	                .withComparisonOperator(ComparisonOperator.EQ.toString())
	                .withAttributeValueList(new AttributeValue(numToFactorize));
	            scanFilter.put("RequestNumber", condition);
	            ScanRequest scanRequest = new ScanRequest(tableName).withScanFilter(scanFilter);
	            ScanResult scanResult = dynamoDB.scan(scanRequest);
	            System.out.println("Result: " + scanResult);
	            
	            String methodCounter = new String();
	            String instructions = new String();
	            String basicBlocks = new String();
	            
	            if(scanResult.getCount() != 0){
	            	methodCounter = scanResult.getItems().get(0).get("MethodCounter").getS();
	            	instructions = scanResult.getItems().get(0).get("Instructions").getS();
	            	basicBlocks = scanResult.getItems().get(0).get("BasicBlocks").getS();
	            }
	            else
	            	methodCounter = "empty";
	            	            
	            System.out.println("Method counter: " + methodCounter);

		 		try {
					requestsBucket.put(new FactorizationRequest(numToFactorize, methodCounter, basicBlocks, instructions));
				} catch (InterruptedException e) {
					// TODO Auto-generated catch block
					e.printStackTrace();
				}
		 		//requestsBucket.offer(new FactorizationRequest(numToFactorize));
		 		//requestsBucket.notify();
			}
	    }
	}
	
	

	public static void main(String args[]) throws Exception{
		
		/*
         * Amazon EC2
         *
         * The AWS EC2 client allows you to create, delete, and administer
         * instances programmatically.
         *
         * In this sample, we use an EC2 client to get a list of all the
         * availability zones, and all instances sorted by reservation id, then 
         * create an instance, list existing instances again, wait a minute and 
         * the terminate the started instance.
         */
		init();
		
		
		//try {
	        Thread instancesListenter = new Thread() {
	            public void run() {
	            	while (true) {
	            		

	    	    		System.out.println("\t\t\t\t\tControl Info");

	    	    		System.out.println("=========================================================================================");

	    	    		System.out.println("===========================================================================================");
	            		
		                try {
		                    ec2.setEndpoint("ec2.us-west-2.amazonaws.com");
		                    cloudWatch.setEndpoint("monitoring.us-west-2.amazonaws.com"); 
		                    describeInstancesRequest = ec2.describeInstances();
		                    reservations = describeInstancesRequest.getReservations();
		                    instances = new ConcurrentHashMap().newKeySet();
		    		        runningInstances =  new HashMap<String,Instance>();
		                    instancesDNS = new HashSet<String>();
		                     
		                    for (Reservation reservation : reservations) {
		                        instances.addAll(reservation.getInstances());
		                    }
		                    
		                	for (Instance instance : instances){
		                    	if(instance.getState().getName().equals("running") && instance.getImageId().equals(INSTANCE_AMI)){
		                    		runningInstances.put(instance.getInstanceId(), instance);
		                    		instancesDNS.add(instance.getPublicDnsName());
		                    		//System.out.println("There is an Amazon EC2 instance(s) running with public DNS "+ instance.getPublicDnsName() +" .");
		                    		
		                    		String lastCPU;
		    	            		if (lastStatePerInstance.containsKey(instance.getInstanceId())){
		    	            			lastCPU = lastStatePerInstance.get(instance.getInstanceId()).getAverage().toString();
		    	            		} else {
		    	            			lastCPU = "None";
		    	            		}
		    	            		System.out.println("The last CPU state of instance " + instance.getPublicDnsName() + " is : " + lastCPU);
		                    		
		                    		
		                    		//launches a new thread to perform the health check
		                    		Runnable healthCheck = new HealthCheckerThread(instance);
		                    		new Thread(healthCheck).start();
		                    	}
		                    }
		
		
		                    System.out.println("You have " + instancesDNS.size() + " Amazon EC2 instance(s) running.");
		                    
		                    System.out.println("\n===========================================");
		    	    		System.out.println("New Cloudwatch Info");
		    	    		System.out.println("===========================================");
		                    
		                    /* TODO total observation time in milliseconds */
		    	            long offsetInMilliseconds = 1000 * 60 * 10;
		    	            Dimension instanceDimension = new Dimension();
		    	            instanceDimension.setName("InstanceId");
		    	            List<Dimension> dims = new ArrayList<Dimension>();
		    	            dims.add(instanceDimension);
		    	            for (Instance instance : runningInstances.values()) {	            		
		    		        		instancesDNS.add(instance.getPublicDnsName());		        		
		    		        	
		    		                String name = instance.getInstanceId();
		    		                String state = instance.getState().getName();
		    		                System.out.println("Instance State : " + state +".");
		    		                if (state.equals("running")) { 
		    		                    System.out.println("running instance id = " + name);
		    		                    instanceDimension.setValue(name);
		    		                    GetMetricStatisticsRequest request = new GetMetricStatisticsRequest()
		    				                .withStartTime(new Date(new Date().getTime() - offsetInMilliseconds))
		    				                .withNamespace("AWS/EC2")
		    				                .withPeriod(60)
		    				                .withMetricName("CPUUtilization")
		    				                .withStatistics("Average")
		    				                .withDimensions(instanceDimension)
		    				                .withEndTime(new Date());
		    		                     GetMetricStatisticsResult getMetricStatisticsResult = cloudWatch.getMetricStatistics(request);
		    		                     List<Datapoint> datapoints = getMetricStatisticsResult.getDatapoints();
		    	
		    		                     //sorts the datapoint list by timestamp
		    		                     datapoints.sort((o1, o2) -> o1.getTimestamp().compareTo(o2.getTimestamp()));
		    		                     for (Datapoint dp : datapoints) {
		    		                       System.out.println("\tCPU utilization for instance " + name + " = " + dp.getAverage() + "\t\t( " + dp.getTimestamp() + " )");
		    		                     }
		    		                    
		    		                     //if the datapoints list is not empty, get the last datapoint and save it as new state for this instance.
		    		                     //else, if it is empty, means that it just started and is not ready to receive requests, so its cpu is simulated as zero.  
		    		                     if (!datapoints.isEmpty()) {
		    		                    	 Datapoint newState = datapoints.get(datapoints.size() - 1);
		    		                    	 lastStatePerInstance.put(instance.getInstanceId(), newState);		                     
		    		                     } else {
		    		                    	 Datapoint tempState = new Datapoint();
		    		                    	 tempState.setAverage((double) 0);
		    		                    	 lastStatePerInstance.put(instance.getInstanceId(), tempState);
		    		                     }
		    		                 }else {
		    		                     System.out.println("instance id = " + name + "\t, this instance is : " + instance.getState());
		    		                 }
		    	                
		    	            }
		    	            
		    	            //every one minute verifies if there are some instances that need to be changed depending on the CPU average of the system
		    	            if(autoScalarRefreshTime){
		    	            	isCpuAverageOverUnderLimit();
		    	            	autoScalarRefreshTime = false;
		    	            }else
		    	            	autoScalarRefreshTime = true;		    	           
		                    		                    
		                    //every 30 seconds perform a CPU and HealthCheck.  
		                    Thread.sleep(30000);
		
		                    System.out.println("\n");
		                    
		                } catch (AmazonServiceException ase) {
		                        System.out.println("Caught Exception: " + ase.getMessage());
		                        System.out.println("Reponse Status Code: " + ase.getStatusCode());
		                        System.out.println("Error Code: " + ase.getErrorCode());
		                        System.out.println("Request ID: " + ase.getRequestId());
		
		                } catch(Exception v) {
		                    System.out.println(v);
		                }
		            }
	            }
	        };
	        
	        
	        Thread requestsBucketExecuter = new Thread() {
	            public void run() {
	            	String newQuery = "";
	            	StringBuilder finalResults = new StringBuilder();
	            	
	            	while (true) {
	            			
	            			FactorizationRequest factorizationRequest;
	            			String numToFactorize="";
	            			String methodCounter = "";
	            			String instructions = "";
	            			String basicBlocks = "";
							try {
								factorizationRequest = requestsBucket.take();
								numToFactorize = factorizationRequest.getNumToFactorize();
								methodCounter = factorizationRequest.getMethodCounter();
								instructions = factorizationRequest.getInstructions();
								basicBlocks = factorizationRequest.getBasicBlocks();
							} catch (InterruptedException e) {
								// TODO Auto-generated catch block
								e.printStackTrace();
							}
							
		            		List<String> healthyInstances = new ArrayList<String>();
		            		List<Instance> healthyInstancesToCompute = new ArrayList<Instance>();
		            		
		            		for (String instanceId : healthStatusPerInstance.keySet()) {
		            			HealthValuesPair healthInfo = healthStatusPerInstance.get(instanceId);
		            			if (healthInfo.getHealthStatus().equals(OPERATIONAL)){
		            				healthyInstancesToCompute.add(healthInfo.instance);
		            				healthyInstances.add(healthInfo.getInstanceDns());
		            			}
		            		}
		            		
		            		//launches a new thread to perform the health check
                    		Runnable requestExecute = new RequestExecuterThread(healthyInstancesToCompute, numToFactorize, methodCounter, instructions, basicBlocks);
                    		new Thread(requestExecute).start();
		            		
		            		/*String dnsToSend = makeDecision(healthyInstancesToCompute, numToFactorize, methodCounter, instructions, basicBlocks);
	
	            			//envia o pedido para cada uma das instancias que estao saudáveis
		            		//for (String instanceDns : healthyInstances) {
		            		if(dnsToSend!=null){	
		            			newQuery = dnsToSend + ":8000/f.html?n=" + numToFactorize;
		        				System.out.println(newQuery);
		        				
		        				try {
		        					URL url = new URL("http://" + newQuery);
		        					BufferedReader br = new BufferedReader(new InputStreamReader(url.openStream()));
		        					String strTemp = "";
		        					while (null != (strTemp = br.readLine())) {
		        						System.out.println(strTemp);
		        						finalResults.append(strTemp);
		        					}
		        				
		        					/
		        					 * Since the answer to the request does not need to be done to the browser the httpexange object "t" may not exist anymore  
		        					 * 
		        					t.sendResponseHeaders(HTTP_OK_STATUS, finalResults.length());
		        					
		        			     	OutputStream newOs = t.getResponseBody();
		        			     	newOs.write(finalResults.toString().getBytes());
		        			 		newOs.close();
		        			 		
		        			 		/
		        				}catch(ConnectException ex){
		        					System.out.println("connection timeout for instance: ");
		        					System.out.println("request should be redirected ");
		        					
		        				} catch (Exception ex) {
		        					ex.printStackTrace();
		        				}
		            		}*/	
		            }
	            }
	        };
	        
	        instancesListenter.start();
	        requestsBucketExecuter.start();
	        
		/*} catch (AmazonServiceException ase) {
	        System.out.println("Caught Exception: " + ase.getMessage());
	        System.out.println("Reponse Status Code: " + ase.getStatusCode());
	        System.out.println("Error Code: " + ase.getErrorCode());
	        System.out.println("Request ID: " + ase.getRequestId());
		}		  */
        
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
	
	
	static public void executeRequest(String numToFactorize) {
		List<String> healthyInstances = new ArrayList<String>();
		for (String instanceId : healthStatusPerInstance.keySet()) {
		//for (HealthValuesPair healthValuePair : healthStatusPerInstance.values()){
			if (healthStatusPerInstance.get(instanceId).getHealthStatus().equals(OPERATIONAL)){
				healthyInstances.add(instanceId);
			}
		}
		
		
		String dnsFromInstance;
    	String newQuery = "";
    	StringBuilder finalResults = new StringBuilder();	
		
		for (Instance instance : runningInstances.values()) {
			if (healthyInstances.contains(instance.getInstanceId())) {
				newQuery = instance.getPublicDnsName() + ":8000/f.html?n=" + numToFactorize;
				System.out.println(newQuery);
				dnsFromInstance = instance.getPublicDnsName();
				
				try {
					URL url = new URL("http://" + newQuery);
					BufferedReader br = new BufferedReader(new InputStreamReader(url.openStream()));
					String strTemp = "";
					while (null != (strTemp = br.readLine())) {
						System.out.println(strTemp);
						finalResults.append(strTemp);
					}
				
					/*
					 * Since the answer to the request does not need to be done to the browser the httpexange object "t" may not exist anymore  
					 * 
					
					t.sendResponseHeaders(HTTP_OK_STATUS, finalResults.length());
					
			     	OutputStream newOs = t.getResponseBody();
			     	newOs.write(finalResults.toString().getBytes());
			 		newOs.close();
			 		
			 		*/
		 		
				} catch (Exception ex) {
					ex.printStackTrace();
				}
			}
		}	
	}
	
	static public void executeHealthCheckRequest(Instance instance) {
		if (!healthStatusPerInstance.containsKey(instance.getInstanceId())) {
			HealthValuesPair healthValuesPair = new HealthValuesPair(OUTOFSERVICE, 0, instance);
			healthStatusPerInstance.put(instance.getInstanceId(), healthValuesPair);
		}
		
		
		String newQuery = "";
    	StringBuilder healthAnswer = new StringBuilder();	
		
    	newQuery = instance.getPublicDnsName() + ":8000/f.html?n=1";			
		try {
			URL url = new URL("http://" + newQuery);
			BufferedReader br = new BufferedReader(new InputStreamReader(url.openStream()));
			String strTemp = "";
			while (null != (strTemp = br.readLine())) {
				//System.out.println(strTemp);
				
				healthAnswer.append(strTemp);
			}

			HealthValuesPair healthValuesPair = healthStatusPerInstance.get(instance.getInstanceId());
				
			if (healthValuesPair != null){
				int numOfChecks = healthValuesPair.getNumHealthChecksDone() + 1;
				if(healthAnswer.length() != 0 ) { 	//pedido retornou com sucesso
					System.out.println("\t\t\t\t\t\t\t\tStatus Checker : >>   Instance " + instance.getInstanceId() + " : positive check");
					if (healthValuesPair.getHealthStatus().equals(OUTOFSERVICE)) {					
						if ( numOfChecks > 1 ) { 
							healthStatusPerInstance.get(instance.getInstanceId()).setHealthStatus(OPERATIONAL);
							healthStatusPerInstance.get(instance.getInstanceId()).setNumHealthChecksDone(0);
							System.out.println("\n\t\t\t\t\t\t\t\tStatus Checker : >>   <<< INSTANCE IS OPERATIONAL (ID : " + instance.getInstanceId() + " ) >>>\n" );
						} else {
							healthStatusPerInstance.get(instance.getInstanceId()).setNumHealthChecksDone(numOfChecks);
							
						}	
					} else {
						healthStatusPerInstance.get(instance.getInstanceId()).setNumHealthChecksDone(0);
					}
				}
			}
	 		
		} catch (ConnectException ex) {
			//ex.printStackTrace();
			//significa que o check falhou
			System.out.println("\t\t\t\t\t\t\t\tStatus Checker : >>   Instance " + instance.getInstanceId() + " : negative check");
			HealthValuesPair healthValuesPair = healthStatusPerInstance.get(instance.getInstanceId());
			int numOfChecks = healthValuesPair.getNumHealthChecksDone() + 1;
			if (healthValuesPair.getHealthStatus().equals(OPERATIONAL)) {					
				if ( numOfChecks > 1 ) {
					healthStatusPerInstance.get(instance.getInstanceId()).setHealthStatus(OUTOFSERVICE);
					healthStatusPerInstance.get(instance.getInstanceId()).setNumHealthChecksDone(0);
					
					healthStatusPerInstance.get(instance.getInstanceId()).setConsecutiveNegativeChecksDone(2);
					
					System.out.println("\n\t\t\t\t\t\t\t\tStatus Checker : >>   <<< INSTANCE IS UNREACHABLE (ID : " + instance.getInstanceId() + " ) >>>\n" );					
					
				} else {
					healthStatusPerInstance.get(instance.getInstanceId()).setNumHealthChecksDone(numOfChecks);
				}	
			} else {
				int numConsecutiveNegativeChecks = healthStatusPerInstance.get(instance.getInstanceId()).getConsecutiveNegativeChecks() + 1;
			
				healthStatusPerInstance.get(instance.getInstanceId()).setNumHealthChecksDone(0);
				healthStatusPerInstance.get(instance.getInstanceId()).setConsecutiveNegativeChecksDone(numConsecutiveNegativeChecks);
				
				if (numConsecutiveNegativeChecks > 3) {
					substituteInstance(instance);
				}
				
			}
			
		} catch (Exception ex) {
			ex.printStackTrace();
		}
	}
	
	private static String makeDecision(List<Instance> instanceList, String numberToFactorize, String methodCounter, String basicBlocks, String instructions){
		//Falta o pensamento de quando a metrica e fixe
		String dnsInstance = new String();
		
		Double maxCPUInstance = 100.0;
		
		for (Instance instance : instanceList) {
			Datapoint dp = lastStatePerInstance.get(instance.getInstanceId());
			if (dp.getAverage() < maxCPUInstance){
				dnsInstance = instance.getPublicDnsName();
				maxCPUInstance = dp.getAverage();
			}
		}
		
		return dnsInstance;
	}

	
	private static class HealthValuesPair {

		private int consecutiveNegativeChecks = 0;
	    private String healthStatus;
	    private int numHealthChecksDone;
	    
	    private Instance instance;
	    private String instanceDns;

	    public HealthValuesPair(String healthStatus, int numHealthChecksDone, Instance instance) {
	        this.healthStatus = healthStatus;
	        this.numHealthChecksDone = numHealthChecksDone;
	        this.instance = instance;
	        this.instanceDns = instance.getPublicDnsName();
	    }
	    
	    public String getHealthStatus() {
	        return healthStatus;
	    }
	    
	    public int getConsecutiveNegativeChecks() {
	    	return consecutiveNegativeChecks;
	    }

	    public int getNumHealthChecksDone() {
	        return numHealthChecksDone;
	    }
	    
	    public String getInstanceDns() {
	        return instanceDns;
	    }
	    
	    public void setHealthStatus(String healthStatus){
	    	this.healthStatus = healthStatus;
	    }
	    
	    public void setNumHealthChecksDone(int numHealthChecksDone){
	    	this.numHealthChecksDone = numHealthChecksDone;
	    }
	    
	    public void setConsecutiveNegativeChecksDone(int consecutiveNegativeChecks){
	    	this.consecutiveNegativeChecks = consecutiveNegativeChecks;
	    }

	}
	
	private static class HealthCheckerThread implements Runnable {
		private Instance instance;
		
		public HealthCheckerThread (Instance instance) {
			this.instance = instance;
		}
		
		public void run(){
			executeHealthCheckRequest(instance);
		}
	}
	
	private static class RequestExecuterThread implements Runnable {
		private Instance instance;
		private List<Instance> healthyInstancesToCompute;
		private String numToFactorize;
		private String methodCounter;
		private String basicBlocks;
		private String instructions;
		
		
		public RequestExecuterThread ( List<Instance> instanceList, String numberToFactorize, String methodCounter, String basicBlocks, String instructions) {
			//this.instance = instance;
			this.healthyInstancesToCompute = instanceList;
			this.numToFactorize = numberToFactorize;
			this.methodCounter = methodCounter;
			this.basicBlocks = basicBlocks;
			this.instructions = instructions;
		}
		
		public void run(){
			String newQuery = "";
			StringBuilder finalResults = new StringBuilder();
    		String dnsToSend = makeDecision(healthyInstancesToCompute, numToFactorize, methodCounter, instructions, basicBlocks);
    		
			//envia o pedido para cada uma das instancias que estao saudáveis
    		//for (String instanceDns : healthyInstances) {
    		if(dnsToSend!=null){	
    			newQuery = dnsToSend + ":8000/f.html?n=" + numToFactorize;
				System.out.println(newQuery);
				
				try {
					URL url = new URL("http://" + newQuery);
					BufferedReader br = new BufferedReader(new InputStreamReader(url.openStream()));
					String strTemp = "";
					while (null != (strTemp = br.readLine())) {
						System.out.println(strTemp);
						finalResults.append(strTemp);
					}
				
					/*
					 * Since the answer to the request does not need to be done to the browser the httpexange object "t" may not exist anymore  
					 * 
					t.sendResponseHeaders(HTTP_OK_STATUS, finalResults.length());
					
			     	OutputStream newOs = t.getResponseBody();
			     	newOs.write(finalResults.toString().getBytes());
			 		newOs.close();
			 		
			 		*/
				}catch(ConnectException ex){
					System.out.println("connection timeout for instance: ");
					System.out.println("request should be redirected ");
					
				} catch (Exception ex) {
					ex.printStackTrace();
				}
    		}	
		}
	}
	
	
	/* =================================================================
	 * =================================================================
	 * 						AutoScaler Methods
	 * =================================================================
	 * ================================================================= */	
	
	static public void createNewInstance() {
		
		//Uncomment to launch new Machines
        /* TODO: configure to use your AMI, key and security group */
        RunInstancesRequest runInstancesRequest = new RunInstancesRequest();
        runInstancesRequest.withImageId(INSTANCE_AMI)
                           .withInstanceType("t2.micro")
                           .withMinCount(1)
                           .withMaxCount(1)
                           .withMonitoring(true)
                           .withKeyName("CNV-machine")
                           .withSecurityGroups("CNV-ssh+http");
        RunInstancesResult runInstancesResult = ec2.runInstances(runInstancesRequest);
        String newInstanceId = runInstancesResult.getReservation().getInstances().get(0).getInstanceId();
        
        System.out.println("\n\t\t\t\t\t\t\t\tAutoScaler : >>   <<< CREATED NEW INSTANCE (ID : " + newInstanceId + " ) >>>\n" );
        
        freshInstanceLaunchTime = new Date().getTime();
	}
	
	static public void deleteInstance() {
		//Deletes the instance with the smallest Cpu average.
		
		
		KeyPair<String, Double> simplePair = new KeyPair("", 100.0);		
		
		for (String instanceId : lastStatePerInstance.keySet()) {
			Datapoint dp = lastStatePerInstance.get(instanceId);
			if (dp.getAverage() < simplePair.getValue()){
				simplePair.setKey(instanceId);
				simplePair.setValue(dp.getAverage());
			}
		}
		
		String instanceIdToDelete = simplePair.getKey();
		
		//removes the instance to be deleted from the healthStatus Map
		if (healthStatusPerInstance.containsKey(instanceIdToDelete)) {
			healthStatusPerInstance.remove(instanceIdToDelete);
		}
		
		TerminateInstancesRequest termInstanceReq = new TerminateInstancesRequest();

        termInstanceReq.withInstanceIds(instanceIdToDelete);

        ec2.terminateInstances(termInstanceReq);
		
		System.out.println("\n\t\t\t\t\t\t\t\tAutoScaler : >>   <<< INSTANCE "+ simplePair.getKey() + " WAS DELETED >>>\n" );
	}
	
	static public void substituteInstance(Instance instance) {
		String instanceId = instance.getInstanceId();
		
		//removes the instance to be deleted from the healthStatus Map
		if (healthStatusPerInstance.containsKey(instanceId)) {
			healthStatusPerInstance.remove(instanceId);
		}
		
		TerminateInstancesRequest termInstanceReq = new TerminateInstancesRequest();
        termInstanceReq.withInstanceIds(instanceId);
        ec2.terminateInstances(termInstanceReq);
		
		System.out.println("\n\t\t\t\t\t\t\t\tUnhealthy Corrector : >>   <<< UNHEALTHY INSTANCE "+ instanceId + " WAS DELETED >>>\n" );
	}
	
	
	static public void isCpuAverageOverUnderLimit() {		
		if (runningInstances.size() < MIN_INSTANCES_DESIRED) {
			
			createNewInstance();
			
		} else {
			double cpuAverage = calculateCurrentCpuAverage();
		
		
			System.out.println(" new average: " + cpuAverage + " /// last Cpu Average State: " + lastCpuAverageState);
			 
			if (cpuAverage > 60 && lastCpuAverageState > 60 && runningInstances.size()< MAX_INSTANCES_DESIRED){
				createNewInstance();
			} else if (cpuAverage < 40 && lastCpuAverageState < 40 && runningInstances.size()> MIN_INSTANCES_DESIRED){
				deleteInstance();
			} 
			
			lastCpuAverageState = cpuAverage;
		}
	}
	
	static public double calculateCurrentCpuAverage(){
		double cpuSum = 0;
		List<String> instancesToRemove = new ArrayList<String>();
		
		for (String instanceId : lastStatePerInstance.keySet()) {
			
			Datapoint dp = lastStatePerInstance.get(instanceId);
			//System.out.println("lastStatePerInstance: " + instanceId + "// " + dp.getAverage() );
			if (runningInstances.keySet().contains(instanceId)){			
				cpuSum += dp.getAverage();
			} else {
				instancesToRemove.add(instanceId);
			}
		}
		
		//Remove previous Instances
		for (String s : instancesToRemove ){
			lastStatePerInstance.remove(s);
		}
		
		return cpuSum/lastStatePerInstance.size();	
	}
	
	private static class KeyPair<String, Double> {

	    private String element0;
	    private Double element1;

	    public static <String, Double> Pair<String, Double> createPair(String element0, Double element1) {
	        return new Pair<String, Double>(element0, element1);
	    }

	    public KeyPair(){
	    	
	    }
	    public KeyPair(String element0, Double element1) {
	        this.element0 = element0;
	        this.element1 = element1;
	    }

	    public String getKey() {
	        return element0;
	    }

	    public Double getValue() {
	        return element1;
	    }
	    
	    public void setKey(String key){
	    	this.element0 = key;
	    }
	    
	    public void setValue(Double value){
	    	this.element1 = value;
	    }

	}
	
}
