package betamindy.util;

import arc.func.Boolf;
import arc.math.*;
import arc.math.geom.*;
import arc.struct.Seq;
import arc.util.*;
import betamindy.world.blocks.distribution.PistonArm;
import mindustry.*;
import mindustry.content.*;
import mindustry.gen.*;
import mindustry.world.*;
import mindustry.world.blocks.storage.*;

import java.util.PriorityQueue;

import static arc.math.geom.Geometry.d4;

public class XeloUtil {
    public Point2[][] origins = new Point2[16][4];

    public void init(){
        for(int size = 1; size <= 16; size++){
            int originx = 0;
            int originy = 0;
            originx += size / 2;
            originy += size / 2;
            originy -= (size - 1);
            for(int side = 0; side < 4; side++){
                int ogx = originx;
                int ogy = originy;
                if(side!=0 && size>1){
                    for (int i = 1; i <= side; i++) {
                        ogx += d4(i).x * (size - 1);
                        ogy += d4(i).y * (size - 1);
                    }
                }
                origins[size-1][side] = new Point2(ogx, ogy);
                print(ogx + "," + ogy);
            }
        }
    }

    /** returns whether a building is allowed to be pushed. */
    public boolean pushable(Building build){
        if(build.dead || (build.block instanceof CoreBlock) || (build.block instanceof PistonArm)) return false;
        return true;
    }

    /** returns whether a block is allowed to be on this tile, disregarding existing pushable buildings and team circles */
    public boolean tileAvalibleTo(@Nullable Tile tile, Block block){
        if(tile == null){
            return false;
        }
        if(tile.build != null){
            return pushable(tile.build);
        }
        if(
            tile.solid() ||
            !tile.floor().placeableOn ||
            (block.requiresWater && tile.floor().liquidDrop != Liquids.water)||
            (tile.floor().isDeep() && !block.floating && !block.requiresWater && !block.placeableLiquid)
        )
        {
            return false;
        }
        return true;
    }

    /** returns whether a tile can be pushed in this direction, disregarding buildings. */
    public boolean canPush(Building build, int direction){
        if(!pushable(build)){return false;}
        Point2 tangent = d4((direction + 1) % 4);
        Point2 o = origins[build.block.size-1][direction];
        for(int i=0; i < build.block.size; i++){ // iterate over forward edge.
            Tile t = build.tile.nearby(o.x + tangent.x *i + d4(direction).x,o.y + tangent.y *i+ d4(direction).y);
            if(!tileAvalibleTo(t, build.block)){
                return false;
            }
        }
        Tile next = build.tile.nearby(d4(direction).x, d4(direction).y);
        if(next == null || !build.block.canPlaceOn(next, build.team)){
            return false;
        }
        return true;
    }

    /**
     * pushes a single building. if obstructed does not push multiple tiles. returns false if its blocked, otherwise true. used as a subtorutine for the function that actually does push all obstructed tiles.
     * @param build the building to be pushed. DO NOT CALL FROM WITHIN THE BUILDING.
     * @param direction number from 0-4 same direction as the block rotation to push the building in.
     */

    /*algorithm:
        scan forward tiles for blockage
        return false if a block exists in forward tiles or tile isnt allowed forward space
        remove building
        readd building.
    */
    public boolean _pushSingle(Building build, int direction){
        direction = direction % 4;
        //don't move the core. >:(  BAD BAD BAD BAD
        if(build.block instanceof CoreBlock){return false;}
        int bx = build.tile.x;
        int by = build.tile.y;
        build.tile.remove();
        //scan forward tiles for blockage
        if(!Build.validPlace(build.block, build.team, bx+d4(direction).x, by+d4(direction).y, build.rotation, false)){
            Vars.world.tile(bx,by).setBlock(build.block, build.team, build.rotation, () -> build);
            return false;
        }

        Vars.world.tile(bx+d4(direction).x, by+d4(direction).y).setBlock(build.block, build.team, build.rotation, () -> build);
        return true;
    }

    //projection of the block's leading edge along a direction.
    private int project(Building build, int direction){
        return (origins[build.block.size-1][direction].x+build.tile.x)* d4(direction).x + (origins[build.block.size-1][direction].y+build.tile.y)* d4(direction).y;
    }

    /** gets all buildings connected to each other in the push direction sorted. if group cannot be pushed because its too large or an unpushable block exists it returns null.
     * @param root the building to be scanned from
     * @param direction number from 0-4 same direction as the block rotation to push the building in.
     * @param max max number of blocks to scan
     * @param bool boolf consumer as a custom selection criteria.
     */

    public @Nullable Seq<Building> _getAllContacted(Building root, int direction, int max, Boolf<Building> bool){
        PriorityQueue<Building> queue = new PriorityQueue<Building>(10, (a, b)->{//require ordering to be projection of the block's leading edge along push direction.
            return Math.round(project(a, direction) - project(b, direction));
        });
        queue.add(root);
        Seq<Building> contacts = new Seq<Building>();
        while(!queue.isEmpty() && contacts.size <= max){
            Building next = queue.poll();
            contacts.add(next);
            Point2 tangent = d4((direction + 1) % 4);
            Point2 o = origins[next.block.size-1][direction];
            for(int i=0; i < next.block.size; i++){ // iterate over forward edge.
                Tile t = next.tile.nearby(o.x + tangent.x * i + d4(direction).x,o.y + tangent.y * i+ d4(direction).y);
                Building b = t.build;
                if(b == null || queue.contains(b)|| contacts.contains(b)){continue;}
                if(!pushable(b) || !bool.get(b)){
                    return null; // if a single block cannot be pushed then the entire group cannot be pushed from the root.
                }
                queue.add(b);
            }
        }
        if(contacts.size<=max){
            return contacts;
        }else{
            return null;
        }
    }

    /** pushes a single building and pushes all blocks behind the pushed block., unlike the previous. */
    public boolean pushBlock(Building build, int direction, int maxBlocks){
        @Nullable Seq<Building> pushing = _getAllContacted(build, direction, maxBlocks, b -> true);
        if(pushing == null){
            return false;
        }
        //scan in reverse
        for(int i = pushing.size - 1; i >= 0; i--){
            if(!canPush(pushing.get(i),direction)){
                return false;
            }
        }
        for(int i = pushing.size-1; i>=0; i--){
            _pushSingle(pushing.get(i), direction);
        }
        return true;
    }

    public void print(String pain){
        Vars.mods.getScripts().log("BetaMindy", pain);
    }
}
