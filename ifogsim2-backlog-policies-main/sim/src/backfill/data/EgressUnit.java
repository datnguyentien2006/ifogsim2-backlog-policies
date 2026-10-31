package backfill.data;
import org.fog.entities.Tuple;

public final class EgressUnit { /*Edge's packet filling in backlog/new queue'*/
	public final Tuple tuple;
	public final boolean isNew; /*True when edge is connecting without disruption*/
	public final int edgeIndex;
	public final double genTime; 
	public static int GlobalSeqCounter=0;
	public EgressUnit(Tuple tuple, double genTime, boolean isNew, int edgeIndex) {
	    this.tuple = tuple;
	    this.genTime = genTime;
	    this.isNew = isNew;
	    this.edgeIndex = edgeIndex;
}
	public int cloudletId() {return tuple == null? -1:tuple.getCloudletId();}
	public double fileSize() {return tuple == null? 0:tuple.getCloudletId();}
	public static void resetSequence(){
		GlobalSeqCounter=0;}
}