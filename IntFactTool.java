import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.Hashtable;
import java.util.List;
import java.util.Map;
import java.util.Vector;

import BIT.highBIT.BasicBlock;
import BIT.highBIT.ClassInfo;
import BIT.highBIT.Instruction;
import BIT.highBIT.InstructionTable;
import BIT.highBIT.Routine;

import com.amazonaws.services.dynamodbv2.AmazonDynamoDBClient;
import com.amazonaws.services.dynamodbv2.model.AttributeValue;

class Branch {
	public int taken;
	public int not_taken;
}

public class IntFactTool {
	static Hashtable branch = null;
	static int pc = 0;
	private static int i_count = 0, b_count = 0, m_count = 0;
	private static List<Map<String, AttributeValue>> itemsToAdd = new ArrayList<Map<String, AttributeValue>>();

	
	public static void main(String argv[]) {
		String infilename = new String(argv[0]);
		String outfilename = new String(argv[1]);
		ClassInfo ci = new ClassInfo(infilename);
		Vector routines = ci.getRoutines();
		
		for (Enumeration e=routines.elements();e.hasMoreElements(); ){
				Routine routine = (Routine) e.nextElement();
				routine.addBefore("IntFactTool", "mcount", new Integer(1));

				Instruction[] instructions = routine.getInstructions();
				for (Enumeration b = routine.getBasicBlocks().elements(); b.hasMoreElements(); ) {
					BasicBlock bb = (BasicBlock) b.nextElement();
                    bb.addBefore("IntFactTool", "count", new Integer(bb.size()));

					Instruction instr = (Instruction)instructions[bb.getEndAddress()];
					short instr_type = InstructionTable.InstructionTypeTable[instr.getOpcode()];
					if (instr_type == InstructionTable.CONDITIONAL_INSTRUCTION) {
						instr.addBefore("IntFactTool", "Offset", new Integer(instr.getOffset()));
						instr.addBefore("IntFactTool", "Branch", new String("BranchOutcome"));
					}
				}
				String method = new String(routine.getMethodName());
				routine.addBefore("IntFactTool", "EnterMethod", method);
				routine.addAfter("IntFactTool", "LeaveMethod", method);
		}
        ci.addAfter("IntFactTool", "printICount", "ci.getClassName()");
        
		ci.write(outfilename);
	}
	
	public static synchronized void printICount(String foo) {
        System.out.println(i_count + " instructions in " + b_count + " basic blocks were executed in " + m_count + " methods.");
    }
    

    public static synchronized void count(int incr) {
        i_count += incr;
        b_count++;
    }

    public static synchronized void mcount(int incr) {
		m_count++;
    }

	public static void EnterMethod(String s) {
		System.out.println("method: " + s);
		branch = new Hashtable();
	}
	
	public static void LeaveMethod(String s) {
		System.out.println("stat for method: " + s);
		for (Enumeration e = branch.keys(); e.hasMoreElements(); ) {
			Integer key = (Integer) e.nextElement();
			Branch b = (Branch) branch.get(key);
			int total = b.taken + b.not_taken;
			System.out.print("PC: " + key);
			System.out.print("\t\ttaken: " + b.taken + " (" + b.taken*100/total + "%)");
			System.out.println("\t\tnot taken: " + b.not_taken + " (" + b.not_taken*100/total + "%)");
		}
	}

	public static void Offset(int offset) {
		pc = offset;
	}

	public static void Branch(int brOutcome) {
		Integer n = new Integer(pc);
		Branch b = (Branch) branch.get(n);
		if (b == null) {
			b = new Branch();
			branch.put(n,b);
		}
		if (brOutcome == 0)
			b.taken++;
		else
			b.not_taken++;
	}
}