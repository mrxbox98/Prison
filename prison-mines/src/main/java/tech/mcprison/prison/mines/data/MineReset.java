package tech.mcprison.prison.mines.data;

import java.text.DecimalFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Random;
import java.util.TreeMap;

import tech.mcprison.prison.Prison;
import tech.mcprison.prison.internal.Player;
import tech.mcprison.prison.internal.World;
import tech.mcprison.prison.mines.PrisonMines;
import tech.mcprison.prison.mines.data.MineScheduler.MineJob;
import tech.mcprison.prison.mines.events.MineResetEvent;
import tech.mcprison.prison.output.Output;
import tech.mcprison.prison.util.BlockType;
import tech.mcprison.prison.util.Location;
import tech.mcprison.prison.util.Text;

public abstract class MineReset
	extends MineData
{
	public static final long MINE_RESET__BROADCAST_RADIUS_BLOCKS = 150;
	
	/**
	 * <p>Minecraft ticks have 20 per seconds, which is 50 MS per tick. 
	 * The value for MINE_RESET__MAX_PAGE_ELASPSED_TIME_MS is intended
	 * to be used as a threshold to determine when to stop placing blocks
	 * and resubmit in the sync job queue to allow other processes to run.
	 * </p>
	 * 
	 * <p>This value, in milliseconds, is not hard-and-fast guaranteed to be 
	 * caught exactly at that time. It is instead used to check to see if
	 * the current process has exceeded this value, which may be many times
	 * greater than this value.
	 * </p>
	 * 
	 * <p>This value is subject to refinement and tuning to ensure better and
	 * more accurate responses.
	 * </p>
	 */
	public static final long MINE_RESET__MAX_PAGE_ELASPSED_TIME_MS = 100;
	
	/**
	 * <p>When placing blocks, this is the block count that is used to check for
	 * the elapsed time.
	 * </p>
	 * 
	 * <p>It does not matter if some of those blocks were placed in a prior 
	 * page, for it is the elapsed time that is important.  It's also important
	 * that there are not too many checks since it will just contribute to lag.
	 * The value needs to be moderately large.
	 * </p>
	 * 
	 * <p>This value is subject to refinement and tuning to ensure better and
	 * more accurate responses.
	 * </p>
	 */
	public static final long MINE_RESET__PAGE_TIMEOUT_CHECK__BLOCK_COUNT = 500;
	
	@Deprecated
	private List<BlockType> randomizedBlocks;
	
	// to replace randomizedBlocks....
	private List<MineTargetBlock> mineTargetBlocks;
	private TreeMap<MineTargetBlockKey, MineTargetBlock> mineTargetBlocksMap;
	
	private int resetPage = 0;
	private int resetPosition = 0;
	
	private int airCountOriginal = 0;
	private int airCount = 0;
	
//	private boolean[] mineAirBlocksOriginal;
//	private boolean[] mineAirBlocksCurrent;

	private long statsResetTimeMS = 0;
	private long statsBlockGenTimeMS = 0;
	private long statsBlockUpdateTimeMS = 0;
	private long statsTeleport1TimeMS = 0;
	private long statsTeleport2TimeMS = 0;
	private long statsMessageBroadcastTimeMS = 0;
	
	
	public MineReset() {
		super();
		
		this.randomizedBlocks = new ArrayList<>();
		
		this.mineTargetBlocks = new ArrayList<>();
		this.mineTargetBlocksMap = new TreeMap<>();
	}
	
    
    /**
     * <p>Optimized the mine reset to focus on itself.  Also set the Y plane to refresh at the top and work its
     * way down.  That way if the play is teleported to the top, it will appear like the whole mine has reset
     * instantly and they will not see the delay from the bottom of the mine working up to the top.  This will
     * also reduce the likelihood of the player falling back in to the mine if there is no spawn set.
     * </p>
     * 
     * <p>The ONLY code that could be asynchronous ran is the random generation of the blocks.  The other  
     * lines of code is using bukkit and/or spigot api calls which MUST be ran synchronously.
     * </p>
     */
    protected void resetSynchonously() {

    	long start = System.currentTimeMillis();
    	long time2 = 0L;
    	
        // The all-important event
        MineResetEvent event = new MineResetEvent(this);
        Prison.get().getEventBus().post(event);
        if (!event.isCanceled()) {
        	
        	try {
        		Optional<World> worldOptional = getWorld();
        		if (!worldOptional.isPresent()) {
        			Output.get().logError("Could not reset mine " + getName() +
        						" because the world it was created in does not exist.");
        			return;
        		}
        		World world = worldOptional.get();
        		
        		// Generate new set of randomized blocks each time:  This is the ONLY thing that can be async!! ;(
        		generateBlockList();
        		
        		setStatsTeleport1TimeMS(
        				teleportAllPlayersOut( getBounds().getyBlockMax() ) );
        		
        		time2 = System.currentTimeMillis();
        		
        		int i = 0;
        		boolean isFillMode = PrisonMines.getInstance().getConfig().fillMode;
        		for (int y = getBounds().getyBlockMax(); y >= getBounds().getyBlockMin(); y--) {
//    			for (int y = getBounds().getyBlockMin(); y <= getBounds().getyBlockMax(); y++) {
        			for (int x = getBounds().getxBlockMin(); x <= getBounds().getxBlockMax(); x++) {
        				for (int z = getBounds().getzBlockMin(); z <= getBounds().getzBlockMax(); z++) {
        					Location targetBlock = new Location(world, x, y, z);
        					
        					if (!isFillMode || isFillMode && targetBlock.getBlockAt().isEmpty()) {
        						targetBlock.getBlockAt().setType(getRandomizedBlocks().get(i++));
        					} 
        				}
        			}
        		}
        		time2 = System.currentTimeMillis() - time2;
        		setStatsBlockUpdateTimeMS( time2 );
        		
        		
        		// If a player falls back in to the mine before it is fully done being reset, 
        		// such as could happen if there is lag or a lot going on within the server, 
        		// this will TP anyone out who would otherwise suffocate.  I hope! lol
        		setStatsTeleport2TimeMS(
        				teleportAllPlayersOut( getBounds().getyBlockMax() ) );
        		
        		// free up memory:
        		getRandomizedBlocks().clear();
        		
        		// Broadcast message to all players within a certain radius of this mine:
        		broadcastResetMessageToAllPlayersWithRadius( MINE_RESET__BROADCAST_RADIUS_BLOCKS );
        		
        	} catch (Exception e) {
        		Output.get().logError("&cFailed to reset mine " + getName(), e);
        	}
        }
        
        long stop = System.currentTimeMillis();
        setStatsResetTimeMS( stop - start );
        
        // Tie to the command stats mode so it logs it if stats are enabled:
        if ( PrisonMines.getInstance().getMineManager().isMineStats() ) {
        	DecimalFormat dFmt = new DecimalFormat("#,##0");
        	Output.get().logInfo("&cMine reset: &7" + getName() + 
        			"&c  Blocks: &7" + dFmt.format( getBounds().getTotalBlockCount() ) + 
        			statsMessage() );
        }
    }

    
    public String statsMessage() {
    	StringBuilder sb = new StringBuilder();
    	DecimalFormat dFmt = new DecimalFormat("#,##0.000");
    	
    	sb.append( "&3 Reset: &7" );
    	sb.append( dFmt.format(getStatsResetTimeMS() / 1000.0d ));
    	
    	sb.append( "&3 BlockGen: &7" );
    	sb.append( dFmt.format(getStatsBlockGenTimeMS() / 1000.0d ));
    	
    	sb.append( "&3 TP1: &7" );
    	sb.append( dFmt.format(getStatsTeleport1TimeMS() / 1000.0d ));

    	sb.append( "&3 BlockUpdate: &7" );
    	sb.append( dFmt.format(getStatsBlockUpdateTimeMS() / 1000.0d ));
    	
    	sb.append( "&3 TP2: &7" );
    	sb.append( dFmt.format(getStatsTeleport2TimeMS() / 1000.0d ));
    	
    	sb.append( "&3 MsgBroadcast: &7" );
    	sb.append( dFmt.format(getStatsMessageBroadcastTimeMS() / 1000.0d ));
    	
    	return sb.toString();
    }

    private void resetStats() {
    	setResetPage( 0 ); 
    	setResetPosition( 0 );
    	
    	setAirCountOriginal( 9 );;
    	setAirCount( 0 );;

    	setStatsResetTimeMS( 0 );
    	setStatsBlockGenTimeMS( 0 );
    	setStatsBlockUpdateTimeMS( 0 );
    	setStatsTeleport1TimeMS( 0 );
    	setStatsTeleport2TimeMS( 0 );
    	setStatsMessageBroadcastTimeMS( 0 );
    }
	
    /**
     * <p>This function teleports players out of existing mines if they are within 
     * their boundaries within the world where the Mine exists.</p>
     * 
     * <p>Using only players within the existing world of the current mine, each
     * player is checked to see if they are within the mine, and if they are they
     * are teleported either to the mine's spawn location, or straight up from the
     * center of the mine, to the top of the mine (assumes air space will exist there).</p>
     * 
     * <p>This function eliminates possible bug of players being teleported from other
     * worlds, and also eliminates the possibility that the destination could
     * ever be null.</p>
     * 
     * @param world - world 
     * @param targetY
     */
    private long teleportAllPlayersOut(int targetY) {
    	long start = System.currentTimeMillis();
    	
    	World world = getBounds().getCenter().getWorld();

    	List<Player> players = (world.getPlayers() != null ? world.getPlayers() : 
    							Prison.get().getPlatform().getOnlinePlayers());
    	for (Player player : players) {
            if ( getBounds().within(player.getLocation()) ) {
            	
            	teleportPlayerOut(player);
            }
        }
    	
    	return System.currentTimeMillis() - start;
    }
    
    /**
     * <p>This function will teleport the player out of a given mine, or to the given
     * mine. It will not confirm if the player is within the mine before trying to 
     * teleport.
     * </p>
     * 
     * <p>This function will teleport the player to the defined spawn location, or it
     * will teleport the player to the center of the mine, but on top of the
     * mine's surface.</p>
     * 
     * <p>If the player target location has an empty block under its feet, it will 
     * then spawn in a single glass block so the player will not take fall damage.
     * If that block is within the mine, it will be reset at a later time when the
     * mine resets and resets that block.  If it is part of spawn for the mine, then
     * the glass block will become part of the landscape.
     * <p>
     * 
     * @param player
     */
    public void teleportPlayerOut(Player player) {
    	Location altTp = new Location( getBounds().getCenter() );
    	altTp.setY( getBounds().getyBlockMax() + 1 );
    	Location target = isHasSpawn() ? getSpawn() : altTp;
    	
    	// Player needs to stand on something.  If block below feet is air, change it to a 
    	// glass block:
    	Location targetGround = new Location( target );
    	targetGround.setY( target.getBlockY() - 1 );
    	if ( targetGround.getBlockAt().isEmpty() ) {
    		targetGround.getBlockAt().setType( BlockType.GLASS );
    	}
    	
    	player.teleport( target );
    	PrisonMines.getInstance().getMinesMessages().getLocalizable("teleported")
    			.withReplacements(this.getName()).sendTo(player);
    }
    
//    /**
//     * <p>This is a temporary fix until the Bounds.within() checks for the
//     * same world.  For now, it is assumed that Bounds.min and Bounds.max are 
//     * the same world, but that may not always be the case.</p>
//     * 
//     * @param w1 First world to compare to
//     * @param w2 Second world to compare to
//     * @return true if they are the same world
//     */
//    private boolean isSameWorld(World w1, World w2) {
//    	// TODO Need to fix Bounds.within() to test for same worlds:
//    	return w1 == null && w2 == null ||
//    			w1 != null && w2 != null &&
//    			w1.getName().equalsIgnoreCase(w2.getName());
//    }

    /**
     * <p>This generation of a new block list for the mines is designed to run asynchronously. 
     * It not only generates what each block should be, it also records what the block location 
     * is.  This allows actual block updates to be performed linearly using the mineTargetBlock
     * List, or to randomly access each block using the mineTargetBlockMap; two keys in to the 
     * same collection of target blocks.
     * </p>
     * 
     * <p>The major use of the mineTargetBlock List is to allow paging of the updates: where a mine
     * can be updated in smaller segments.  The actual update must be ran synchronously and in small
     * segments.  
     * </p>
     * 
     * <p>Set the Y plane to refresh at the top and work its way down.  That way if the play is 
     * teleported to the top, it will appear like the whole mine has reset instantly and they will 
     * not see the delay from the bottom of the mine working up to the top.  This will also reduce
     * the likelihood of the player falling back in to the mine if there is no spawn set.
     * </p>
     * 
     */
    protected void generateBlockListAsync() {
    	
		long start = System.currentTimeMillis();
		
		// Reset stats:
		resetStats();
		
		Random random = new Random();
		
		// Clear the mineTargetBlocks list:
		getMineTargetBlocks().clear();
		
//		// Reset the mineAirBlocks to all false values:
//		boolean[] mAirBlocks = new boolean[ getBounds().getTotalBlockCount() ];
//		// Arrays.fill(  mAirBlocks, false ); // redundant but prevents nulls if were Boolean
//		setMineAirBlocksOriginal( mAirBlocks );
		
		int airCount = 0;
		
		for (int y = getBounds().getyBlockMax(); y >= getBounds().getyBlockMin(); y--) {
			for (int x = getBounds().getxBlockMin(); x <= getBounds().getxBlockMax(); x++) {
				for (int z = getBounds().getzBlockMin(); z <= getBounds().getzBlockMax(); z++) {
					
					BlockType blockType = randomlySelectBlock( random );
					
					MineTargetBlock mtb = new MineTargetBlock( blockType, x, y, z);
					
					getMineTargetBlocks().add( mtb );
					getMineTargetBlocksMap().put( mtb.getBlockKey(), mtb );
					
					if ( blockType == BlockType.AIR ) {
//						mAirBlocks[i++] = true;
						airCount++;
					}
				}
			}
		}
		
		setAirCountOriginal( airCount );
		setAirCount( airCount );
		
		setResetPosition( 0 );
		
		long stop = System.currentTimeMillis();
		setStatsBlockGenTimeMS( stop - start );
		
    }
    
    /**
     * <p>Yeah I know, it has async in the name of the function, but it still can only
     * be ran synchronously.  The async part implies this is the reset "part" for the
     * async workflow.
     * </p>
     * 
     * <p>Before this part is ran, the resetSynchonously() function must be ran
     * to regenerate the new block list.
     * </p>
     *  
     */
    protected void resetAsynchonously() {
    	boolean canceled = false;
    	
    	if ( getResetPage() == 0 ) {
    		canceled = resetAsynchonouslyInitiate();
    	}
    	
    	if ( !canceled ) {
    		resetAsynchonouslyUpdate();
    		
    		if ( getResetPosition() == getMineTargetBlocks().size() ) {
    			// Done resetting the mine... wrap up:
    			
           		
        		// If a player falls back in to the mine before it is fully done being reset, 
        		// such as could happen if there is lag or a lot going on within the server, 
        		// this will TP anyone out who would otherwise suffocate.  I hope! lol
        		setStatsTeleport2TimeMS(
        				teleportAllPlayersOut( getBounds().getyBlockMax() ) );
        		

        		
        		// Broadcast message to all players within a certain radius of this mine:
        		broadcastResetMessageToAllPlayersWithRadius( MINE_RESET__BROADCAST_RADIUS_BLOCKS );

                
                // Tie to the command stats mode so it logs it if stats are enabled:
                if ( PrisonMines.getInstance().getMineManager().isMineStats() ) {
                	DecimalFormat dFmt = new DecimalFormat("#,##0");
                	Output.get().logInfo("&cMine reset: &7" + getName() + 
                			"&c  Blocks: &7" + dFmt.format( getBounds().getTotalBlockCount() ) + 
                			statsMessage() );
                }
    		} else {
    			//TODO resubmit... 
    			
    			
    		}
    	}
    	
    	// NOTE: blocks already generated in generateBlockListAsync():
    	
//    	long start = System.currentTimeMillis();
//    	
//        // The all-important event
//        MineResetEvent event = new MineResetEvent(this);
//        Prison.get().getEventBus().post(event);
//        if (!event.isCanceled()) {
//        	
//        	try {
//        		Optional<World> worldOptional = getWorld();
//        		if (!worldOptional.isPresent()) {
//        			Output.get().logError("Could not reset mine " + getName() +
//        						" because the world it was created in does not exist.");
//        			return;
//        		}
//        		//World world = worldOptional.get();
//        		
//        		setStatsTeleport1TimeMS(
//        				teleportAllPlayersOut( getBounds().getyBlockMax() ) );
//        		
//        		
//        		resetAsynchonouslyUpdate();
//        		
//        		
//        		// If a player falls back in to the mine before it is fully done being reset, 
//        		// such as could happen if there is lag or a lot going on within the server, 
//        		// this will TP anyone out who would otherwise suffocate.  I hope! lol
//        		setStatsTeleport2TimeMS(
//        				teleportAllPlayersOut( getBounds().getyBlockMax() ) );
//        		
//
//        		
//        		// Broadcast message to all players within a certain radius of this mine:
//        		broadcastResetMessageToAllPlayersWithRadius( MINE_RESET_BROADCAST_RADIUS_BLOCKS );
//        		
//        	} catch (Exception e) {
//        		Output.get().logError("&cFailed to reset mine " + getName(), e);
//        	}
//        }
//        
//        long stop = System.currentTimeMillis();
//        setStatsResetTimeMS( stop - start );
        
//        // Tie to the command stats mode so it logs it if stats are enabled:
//        if ( PrisonMines.getInstance().getMineManager().isMineStats() ) {
//        	DecimalFormat dFmt = new DecimalFormat("#,##0");
//        	Output.get().logInfo("&cMine reset: &7" + getName() + 
//        			"&c  Blocks: &7" + dFmt.format( getBounds().getTotalBlockCount() ) + 
//        			statsMessage() );
//        }
    }

    private boolean resetAsynchonouslyInitiate() {
    	boolean canceled = false;
    	
    	long start = System.currentTimeMillis();
    	
        // The all-important event
        MineResetEvent event = new MineResetEvent(this);
        Prison.get().getEventBus().post(event);
        
        canceled = event.isCanceled();
        if (!canceled) {
        	
        	try {
        		Optional<World> worldOptional = getWorld();
        		if (!worldOptional.isPresent()) {
        			Output.get().logError("Could not reset mine " + getName() +
        						" because the world it was created in does not exist.");
        			canceled = true;
        		} else {
        			
        			setStatsTeleport1TimeMS(
        					teleportAllPlayersOut( getBounds().getyBlockMax() ) );
        		}
        		
        	} catch (Exception e) {
        		Output.get().logError("&cFailed to reset mine " + getName(), e);
        		canceled = true;
        	}
        }
        
        long stop = System.currentTimeMillis();
        setStatsResetTimeMS( stop - start );
        
    	return canceled;
    }
    

    private void resetAsynchonouslyUpdate() {
		World world = getBounds().getCenter().getWorld();
		
//		setStatsTeleport1TimeMS(
//				teleportAllPlayersOut( getBounds().getyBlockMax() ) );
		
		long start = System.currentTimeMillis();
		
		boolean isFillMode = PrisonMines.getInstance().getConfig().fillMode;
		
		int i = getResetPosition();
		for ( ; i < getMineTargetBlocks().size(); i++ )
		{
			MineTargetBlock target = getMineTargetBlocks().get(i);
			
			Location targetBlock = new Location(world, 
					target.getBlockKey().getX(), target.getBlockKey().getY(), 
					target.getBlockKey().getZ());
			
			if (!isFillMode || isFillMode && targetBlock.getBlockAt().isEmpty()) {
				targetBlock.getBlockAt().setType(target.getBlockType());
			} 
			
			/**
			 * About every 500 blocks, check to see if the current wall time spent is greater than
			 * the threshold.  If it is greater, then end the update and let it resubmit.  
			 * It does not matter how many blocks were actually updated during this "page", 
			 * but it is more important the actual elapsed time.  This is to allow other
			 * processes to get processing time and to eliminate possible lagging.
			 */
			if ( i % MINE_RESET__PAGE_TIMEOUT_CHECK__BLOCK_COUNT == 0 ) {
				long elapsed = start = System.currentTimeMillis();
				if ( elapsed > MINE_RESET__MAX_PAGE_ELASPSED_TIME_MS ) {
					break;
				}
			}
		}
		setResetPosition( i );
		
		setResetPage( getResetPage() + 1 ); 
		
		long time = System.currentTimeMillis() - start;
		setStatsBlockUpdateTimeMS( time + getStatsBlockUpdateTimeMS() );
		setStatsResetTimeMS( time + getStatsResetTimeMS() );

    }
    
    /**
     * Generates blocks for the specified mine and caches the result.
     * 
     * The random chance is now calculated upon a double instead of integer.
     *
     * @param mine the mine to randomize
     */
    private void generateBlockList() {
    	long start = System.currentTimeMillis();
    	
        Random random = new Random();
        
        getRandomizedBlocks().clear();
        
        for (int i = 0; i < getBounds().getTotalBlockCount(); i++) {
        	BlockType blockType = randomlySelectBlock( random );
            getRandomizedBlocks().add(blockType);
        }
        long stop = System.currentTimeMillis();
        
        setStatsBlockGenTimeMS( stop - start );
        
//        Output.get().logInfo("&cMine reset: " + getName() + " generated " + getBounds().getTotalBlockCount() + 
//        		" blocks in " + getStatsBlockGenTimeMS() + " ms");
    }


	private BlockType randomlySelectBlock( Random random )
	{
		double chance = random.nextDouble() * 100.0d;
		
		BlockType value = BlockType.AIR;
		for (Block block : getBlocks()) {
		    if (chance <= block.getChance()) {
		        value = block.getType();
		        break;
		    } else {
		        chance -= block.getChance();
		    }
		}
		return value;
	}
    
    
    private void broadcastResetMessageToAllPlayersWithRadius(long radius) {
    	long start = System.currentTimeMillis();
    	
    	World world = getBounds().getCenter().getWorld();
    	
    	List<Player> players = (world.getPlayers() != null ? world.getPlayers() : 
    							Prison.get().getPlatform().getOnlinePlayers());
    	for (Player player : players) {
            if ( getBounds().within(player.getLocation(), radius)) {
            	
            	// TODO this message needs to have a placeholder for the mine's name:
//            	PrisonMines.getInstance().getMinesMessages()
//		                .getLocalizable("reset_message_mine").withReplacements( getName() )
//		                .sendTo(player);
            	
            	player.sendMessage( "The mine " + getName() + " has just reset." );
            }
    	}
    	
        long stop = System.currentTimeMillis();
        
        setStatsMessageBroadcastTimeMS( stop - start );
    }
    
    protected void broadcastPendingResetMessageToAllPlayersWithRadius(MineJob mineJob, long radius) {
    	World world = getBounds().getCenter().getWorld();
    	List<Player> players = (world.getPlayers() != null ? world.getPlayers() : 
    							Prison.get().getPlatform().getOnlinePlayers());
    	for (Player player : players) {
            if ( getBounds().within(player.getLocation(), radius)) {
            	
            	// TODO this message needs to have a placeholder for the mine's name:
//            	PrisonMines.getInstance().getMinesMessages()
//		                .getLocalizable("reset_warning")
//		                .withReplacements( Text.getTimeUntilString(mineJob.getResetInSec() * 1000) )
//		                .sendTo(player);
//            	
            	player.sendMessage( "The mine " + getName() + " will reset in " + 
            				Text.getTimeUntilString(mineJob.getResetInSec() * 1000) );
            }
    	}
    }

    @Deprecated
	public List<BlockType> getRandomizedBlocks()
	{
		return randomizedBlocks;
	}
    @Deprecated
	public void setRandomizedBlocks( List<BlockType> randomizedBlocks )
	{
		this.randomizedBlocks = randomizedBlocks;
	}

	public List<MineTargetBlock> getMineTargetBlocks()
	{
		return mineTargetBlocks;
	}
	public void setMineTargetBlocks( List<MineTargetBlock> mineTargetBlocks )
	{
		this.mineTargetBlocks = mineTargetBlocks;
	}

	public TreeMap<MineTargetBlockKey, MineTargetBlock> getMineTargetBlocksMap()
	{
		return mineTargetBlocksMap;
	}
	public void setMineTargetBlocksMap( TreeMap<MineTargetBlockKey, MineTargetBlock> mineTargetBlocksMap )
	{
		this.mineTargetBlocksMap = mineTargetBlocksMap;
	}
	

//	public boolean[] getMineAirBlocksOriginal()
//	{
//		return mineAirBlocksOriginal;
//	}
//	public void setMineAirBlocksOriginal( boolean[] mineAirBlocksOriginal )
//	{
//		this.mineAirBlocksOriginal = mineAirBlocksOriginal;
//	}
//
//	public boolean[] getMineAirBlocksCurrent()
//	{
//		return mineAirBlocksCurrent;
//	}
//	public void setMineAirBlocksCurrent( boolean[] mineAirBlocksCurrent )
//	{
//		this.mineAirBlocksCurrent = mineAirBlocksCurrent;
//	}

	public int getResetPage()
	{
		return resetPage;
	}
	public void setResetPage( int resetPage )
	{
		this.resetPage = resetPage;
	}
	
	public int getResetPosition()
	{
		return resetPosition;
	}
	public void setResetPosition( int resetPosition )
	{
		this.resetPosition = resetPosition;
	}

	public int getAirCountOriginal()
	{
		return airCountOriginal;
	}


	public void setAirCountOriginal( int airCountOriginal )
	{
		this.airCountOriginal = airCountOriginal;
	}


	public int getAirCount()
	{
		return airCount;
	}


	public void setAirCount( int airCount )
	{
		this.airCount = airCount;
	}


	public long getStatsResetTimeMS()
	{
		return statsResetTimeMS;
	}
	public void setStatsResetTimeMS( long statsResetTimeMS )
	{
		this.statsResetTimeMS = statsResetTimeMS;
	}

	public long getStatsBlockGenTimeMS()
	{
		return statsBlockGenTimeMS;
	}
	public void setStatsBlockGenTimeMS( long statsBlockGenTimeMS )
	{
		this.statsBlockGenTimeMS = statsBlockGenTimeMS;
	}

	public long getStatsBlockUpdateTimeMS()
	{
		return statsBlockUpdateTimeMS;
	}
	public void setStatsBlockUpdateTimeMS( long statsBlockUpdateTimeMS )
	{
		this.statsBlockUpdateTimeMS = statsBlockUpdateTimeMS;
	}

	public long getStatsTeleport1TimeMS()
	{
		return statsTeleport1TimeMS;
	}
	public void setStatsTeleport1TimeMS( long statsTeleport1TimeMS )
	{
		this.statsTeleport1TimeMS = statsTeleport1TimeMS;
	}

	public long getStatsTeleport2TimeMS()
	{
		return statsTeleport2TimeMS;
	}
	public void setStatsTeleport2TimeMS( long statsTeleport2TimeMS )
	{
		this.statsTeleport2TimeMS = statsTeleport2TimeMS;
	}

	public long getStatsMessageBroadcastTimeMS()
	{
		return statsMessageBroadcastTimeMS;
	}
	public void setStatsMessageBroadcastTimeMS( long statsMessageBroadcastTimeMS )
	{
		this.statsMessageBroadcastTimeMS = statsMessageBroadcastTimeMS;
	}
    
}