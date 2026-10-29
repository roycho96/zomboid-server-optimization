package roy.pzcore;
import java.util.Arrays;
import zombie.core.math.PZMath;
import zombie.network.GameServer;
public final class ServerDirectionMemo {
    private static final int SLOTS=65536;
    private static volatile Thread owner;
    private static int depth;
    private static long[] keys;
    private static float[] values;
    private static boolean[] valid;
    private ServerDirectionMemo() {}
    public static int enter() {
        Thread current=Thread.currentThread();
        if(!GameServer.server||current!=GameServer.mainThread)return -1;
        if(owner==current&&depth>0)return ++depth;
        if(!CoreSettings.SERVER_DIRECTION_MEMO)return -1;
        if(keys==null){
            long[] createdKeys=new long[SLOTS];
            float[] createdValues=new float[SLOTS];
            boolean[] createdValid=new boolean[SLOTS];
            values=createdValues;valid=createdValid;keys=createdKeys;
        }
        depth=1;
        owner=current;
        return 1;
    }
    public static void leave(int token) {
        if(token<0||Thread.currentThread()!=owner)return;
        depth=depth==token?depth-1:0;
        if(depth==0)owner=null;
    }
    public static void reset(){if(valid!=null)Arrays.fill(valid,false);}
    public static float direction(float x,float y) {
        Thread current=Thread.currentThread();
        if(current!=owner||depth!=1||!CoreSettings.SERVER_DIRECTION_MEMO||!GameServer.server||current!=GameServer.mainThread)return original(x,y);
        long key=((long)Float.floatToRawIntBits(x)<<32)|(Float.floatToRawIntBits(y)&0xffffffffL);
        long mixed=key^(key>>>33);mixed*=0xff51afd7ed558ccdL;
        int index=(int)(mixed^(mixed>>>33))&(SLOTS-1);
        if(valid[index]&&keys[index]==key)return values[index];
        float value=original(x,y);
        keys[index]=key;values[index]=value;valid[index]=true;
        return value;
    }
    private static float original(float x,float y){
        return PZMath.wrap((float)Math.atan2(y,x),(float)-Math.PI,(float)Math.PI);
    }
}