import java.util.Date;
import java.sql.Timestamp;
import java.util.Calendar;

public class FactorizationRequest {

	private Timestamp timestamp;
	private String numToFactorize;
	private String methodCounter;
	private String instructions;
	private String basicBlocks;
	
	public FactorizationRequest (String numToFactorize, String methodCounter, String instructions, String basicBlocks) {
		timestamp = new Timestamp(Calendar.getInstance().getTime().getTime());
		this.numToFactorize = numToFactorize;
		this.methodCounter = methodCounter;
		this.instructions = instructions;
		this.basicBlocks = basicBlocks;
	}
	
	public String getNumToFactorize() {
		return this.numToFactorize;
	}
	
	public String getMethodCounter(){
		return this.methodCounter;
	}
	
	public String getInstructions(){
		return this.instructions;
	}
	
	public String getBasicBlocks(){
		return this.basicBlocks;
	}
}