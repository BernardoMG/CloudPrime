import java.io.BufferedReader;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.amazonaws.AmazonClientException;
import com.amazonaws.AmazonServiceException;
import com.amazonaws.auth.AWSCredentials;
import com.amazonaws.auth.profile.ProfileCredentialsProvider;
import com.amazonaws.regions.Region;
import com.amazonaws.regions.Regions;
import com.amazonaws.services.dynamodbv2.AmazonDynamoDBClient;
import com.amazonaws.services.dynamodbv2.model.AttributeValue;
import com.amazonaws.services.dynamodbv2.model.DescribeTableRequest;
import com.amazonaws.services.dynamodbv2.model.PutItemRequest;
import com.amazonaws.services.dynamodbv2.model.PutItemResult;
import com.amazonaws.services.dynamodbv2.model.TableDescription;


public class FactDynamoDB {
    
    static AmazonDynamoDBClient dynamoDB;
    static List<Map<String, AttributeValue>> itemsToAdd =  new ArrayList<Map<String, AttributeValue>>();

    
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
        dynamoDB = new AmazonDynamoDBClient(credentials);
        Region usWest2 = Region.getRegion(Regions.US_WEST_2);
        dynamoDB.setRegion(usWest2);
    }
    
    public static void parseFile(String filename){
        File file = new File(filename); //import java.io.File
        BufferedReader reader = null;
        String[] numberToFactorize = null;
        String methodCounter="";
        String instructions="";
        String basicBlocks="";
        String requestNumber="";

        try{
            reader = new BufferedReader(new FileReader(file));
            String lineFromFile = null;
            
            while((lineFromFile = reader.readLine()) != null){
                if(lineFromFile.contains("Number:")){
                    numberToFactorize = lineFromFile.split(" ", -1);
                    requestNumber=numberToFactorize[1];
                }
                if(lineFromFile.contains("instructions")){
                    numberToFactorize = lineFromFile.split(" ", -1);
                    methodCounter=numberToFactorize[9];
                    instructions=numberToFactorize[0];
                    basicBlocks=numberToFactorize[3];
                }
            }
        }catch(FileNotFoundException  e){ //import exception
            e.printStackTrace();
        }catch(IOException e){  //import exception
            e.printStackTrace();
        }finally{
            try{
                if(reader != null){
                    reader.close();
                }
            }catch(IOException e){}
        }
        itemsToAdd.add(newItem(requestNumber, methodCounter, instructions, basicBlocks));
    }

    public static void main(String[] args) throws Exception{
        FactDynamoDB db = new FactDynamoDB();
        db.init();
        
        db.parseFile(args[0]);
        try {
            String tableName = "Metrics";

            // Describe our new table
            DescribeTableRequest describeTableRequest = new DescribeTableRequest().withTableName(tableName);
            TableDescription tableDescription = dynamoDB.describeTable(describeTableRequest).getTable();
            System.out.println("Table Description: " + tableDescription);

            // Add an item
            for(Map<String, AttributeValue> item : itemsToAdd){
                PutItemRequest putItemRequest = new PutItemRequest(tableName, item);
                PutItemResult putItemResult = dynamoDB.putItem(putItemRequest);
                System.out.println("Result: " + putItemResult);
            }

            // Add another item
            /*item = newItem("122221456642", "1980", "20");
            putItemRequest = new PutItemRequest(tableName, item);
            putItemResult = dynamoDB.putItem(putItemRequest);
            System.out.println("Result: " + putItemResult);*/

            //Example of a query on DynamoDB
            // Scan items for movies with a year attribute greater than 1985
            /*HashMap<String, Condition> scanFilter = new HashMap<String, Condition>();
            Condition condition = new Condition()
                .withComparisonOperator(ComparisonOperator.GT.toString())
                .withAttributeValueList(new AttributeValue().withS("1980"));
            scanFilter.put("TimeToExecute", condition);
            ScanRequest scanRequest = new ScanRequest(tableName).withScanFilter(scanFilter);
            ScanResult scanResult = dynamoDB.scan(scanRequest);
            System.out.println("Result: " + scanResult);*/

        } catch (AmazonServiceException ase) {
            System.out.println("Caught an AmazonServiceException, which means your request made it "
                    + "to AWS, but was rejected with an error response for some reason.");
            System.out.println("Error Message:    " + ase.getMessage());
            System.out.println("HTTP Status Code: " + ase.getStatusCode());
            System.out.println("AWS Error Code:   " + ase.getErrorCode());
            System.out.println("Error Type:       " + ase.getErrorType());
            System.out.println("Request ID:       " + ase.getRequestId());
        } catch (AmazonClientException ace) {
            System.out.println("Caught an AmazonClientException, which means the client encountered "
                    + "a serious internal problem while trying to communicate with AWS, "
                    + "such as not being able to access the network.");
            System.out.println("Error Message: " + ace.getMessage());
        }
    }
    
    private static Map<String, AttributeValue> newItem(String requestNumber, String methodCounter, String instructions, String basicBlocks) {
        Map<String, AttributeValue> item = new HashMap<String, AttributeValue>();
        item.put("RequestNumber", new AttributeValue(requestNumber));
        item.put("MethodCounter", new AttributeValue(methodCounter));
        item.put("Instructions", new AttributeValue(instructions));
        item.put("BasicBlocks", new AttributeValue(basicBlocks));
        return item;
    }
}

