import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.PrintStream;
import java.io.PrintWriter;
import java.io.UnsupportedEncodingException;
import java.math.BigInteger;
import java.util.ArrayList;

public class IntFactorization {

  private BigInteger zero = new BigInteger("0");
  private BigInteger one = new BigInteger("1");
  private BigInteger divisor = new BigInteger("2");
  private ArrayList<BigInteger> factors = new ArrayList<BigInteger>();
  
  ArrayList<BigInteger>  calcPrimeFactors(BigInteger num) {
 
    if (num.compareTo(one)==0) {
      return factors;
    }

    while(num.remainder(divisor).compareTo(zero)!=0) {
      divisor = divisor.add(one);
    }

    factors.add(divisor);
    return calcPrimeFactors(num.divide(divisor));
  }


  public static void main(String[] args) throws FileNotFoundException, UnsupportedEncodingException {
  	
    IntFactorization obj = new IntFactorization();
    int i = 0;
    String threadId = args[1];
    
    PrintStream console = System.out;

    PrintWriter writer = new PrintWriter("results"+args[0]+".txt", "UTF-8");
    
    File file1 = new File("Metrics" + args[0] + ".txt");
    FileOutputStream fos = new FileOutputStream(file1);
    PrintStream ps = new PrintStream(fos);
    System.setOut(ps);

	System.out.println("Number: " + new BigInteger(args[0]));
	
	writer.println("Factoring " + args[0] + "...");
    ArrayList<BigInteger> factors = obj.calcPrimeFactors(new BigInteger(args[0]));

 
    writer.println("");
    writer.println("The prime factors of " + args[0] + " are ");
    for (BigInteger bi: factors) {
      i++;
      writer.println(bi.toString());
      if (i == factors.size()) {
    	  writer.println(".");
      } else {
    	  writer.println(", ");
      }
    }
    writer.println("");
    writer.close();
  }
}
