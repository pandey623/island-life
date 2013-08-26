package com.friendlyblob.islandlife.server.network;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.ByteBuffer;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.locks.ReentrantLock;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import org.mmocore.network.MMOClient;
import org.mmocore.network.MMOConnection;
import org.mmocore.network.ReceivablePacket;

import com.friendlyblob.islandlife.server.Config;
import com.friendlyblob.islandlife.server.network.packets.ActionFailed;
import com.friendlyblob.islandlife.server.network.packets.GameServerPacket;
import com.friendlyblob.islandlife.server.network.packets.ServerClose;

public class GameClient extends MMOClient<MMOConnection<GameClient>> implements Runnable{
	protected static final Logger log = Logger.getLogger(GameClient.class.getName());
	protected static final Logger logAccounting = Logger.getLogger("accounting");

	public static enum GameClientState {
		CONNECTED,
		AUTHORIZED,
		IN_GAME
	}
	
	private GameClientState state;
	
	// Connection info
	private final InetAddress address;
	private String accountName;
	private ClientStats stats;
	
	// Packet queue
	private final ArrayBlockingQueue<ReceivablePacket<GameClient>> packetQueue;
	private final ReentrantLock queueLock = new ReentrantLock();
	
	private GameServerPacket aditionalClosePacket;
	
	private final GameCrypt crypt;
	
	public GameClient(MMOConnection<GameClient> con) {
		super(con);

		stats = new ClientStats(); 
		packetQueue = new ArrayBlockingQueue<>(Config.CLIENT_PACKET_QUEUE_SIZE);
		crypt = new GameCrypt();
		
		
		try {
			address = con != null ? con.getInetAddress() : InetAddress.getLocalHost();
		} catch (UnknownHostException e) {
			throw new Error("Unable to determine localhost address.");
		}
	}
	
	public byte[] enableCrypt()
	{
		byte[] key = BlowFishKeygen.getRandomKey();
		crypt.setKey(key);
		return key;
	}
	
	/**
	 * True if flood detected, or queue overflow detected and queue still not empty.
	 * @return false if client can receive packets.
	 */
	public boolean dropPacket(){
		// flood protection
		if (getStats().countPacket(packetQueue.size())) {
			sendPacket(ActionFailed.STATIC_PACKET);
			return true;
		}
		
		return getStats().dropPacket();
	}
	
	public void sendPacket(GameServerPacket gameServerPacket) {
		if (gameServerPacket == null) {
			return;
		}
		
		getConnection().sendPacket(gameServerPacket);
		gameServerPacket.runImpl();
	}
	
	public void close(GameServerPacket gameServerPacket) {
		if (getConnection() == null) {
			return; // ofline shop
		}
		
		if (aditionalClosePacket != null) {
			getConnection().close(new GameServerPacket[] {
				aditionalClosePacket,
				gameServerPacket
			});
		} else {
			getConnection().close(gameServerPacket);
		}
	}
	
	public void close(GameServerPacket[] gameServerPacketArray)
	{
		if (getConnection() == null) {
			return; // ofline shop
		}
		
		getConnection().close(gameServerPacketArray);
	}

	@Override
	public void run() {
		if (!queueLock.tryLock()) {
			return;
		}
		
		try {
			int count = 0;
			ReceivablePacket<GameClient> packet;
			while (true) {
				packet = packetQueue.poll();
				
				if (packet == null) {
					return;
				}
				
				try {
					packet.run();
				} catch (Exception e) {
					log.severe("Exception during execution " + packet.getClass().getSimpleName() + ", client: " + toString() + "," + e.getMessage());
				}
				
				count++;
				if (getStats().countBurst(count)) {
					return;
				}
			}
		}
		finally {
			queueLock.unlock();
		}
	}

	@Override
	public boolean decrypt(ByteBuffer buf, int size) {
		crypt.decrypt(buf.array(), buf.position(), size);
		return true;
	}

	@Override
	public boolean encrypt(ByteBuffer buf, int size) {
		crypt.encrypt(buf.array(), buf.position(), size);
		buf.position(buf.position() + size);
		return true;
	}

	@Override
	protected void onDisconnection() {

		try {
			// TODO: Execute disconnect task
			System.out.println("DC :O");
		} catch (RejectedExecutionException e) {
			
		}
	}

	@Override
	protected void onForcedDisconnection() {
		LogRecord record = new LogRecord(Level.WARNING, "Disconnected abnormally");
		
		record.setParameters(new Object[] {
			this
		});
		
		logAccounting.log(record);
	}
	
	@Override
	public String toString()
	{
		return accountName;
	}
	
	public GameClientState getState(){
		return state;
	}
	
	public void setState(GameClientState pState)
	{
		if (state != pState)
		{
			state = pState;
			packetQueue.clear();
		}
	}
	
	/**
	 * Counts buffer underflow exceptions.
	 */
	public void onBufferUnderflow()
	{
		if (getStats().countUnderflowException()) {
			log.severe("Client " + toString() + " - Disconnected: Too many buffer underflow exceptions.");
			closeNow();
			return;
		} if (state == GameClientState.CONNECTED) {
			// in CONNECTED state kick client immediately
			
			if (Config.PACKET_HANDLER_DEBUG) {
				log.severe("Client " + toString() + " - Disconnected, too many buffer underflows in non-authed state.");
			}
			closeNow();
		}
	}
	
	/**
	 * Counts unknown packets
	 */
	public void onUnknownPacket()
	{
		if (getStats().countUnknownPacket()) {
			log.severe("Client " + toString() + " - Disconnected: Too many unknown packets.");
			closeNow();
			return;
		}
		
		if (state == GameClientState.CONNECTED) {
			// in CONNECTED state kick client immediately
			if (Config.PACKET_HANDLER_DEBUG) {
				log.severe("Client " + toString() + " - Disconnected, too many unknown packets in non-authed state.");
			}
			closeNow();
		}
	}
	
	/**
	 * Close client connection with {@link ServerClose} packet
	 */
	public void closeNow() {
		close(ServerClose.STATIC_PACKET);
		
		synchronized (this) {
			// TODO: Execute cleanup task
		}
	}
	
	/**
	 * Add packet to the queue and start worker thread if needed
	 * @param packet
	 */
	public void execute(ReceivablePacket<GameClient> packet)
	{
		if (getStats().countFloods()) {
			log.severe("Client " + toString() + " - Disconnected, too many floods:" + getStats().longFloods + " long and " + getStats().shortFloods + " short.");
			closeNow();
			return;
		}
		
		if (!packetQueue.offer(packet)) {
			if (getStats().countQueueOverflow()) {
				log.severe("Client " + toString() + " - Disconnected, too many queue overflows.");
				closeNow();
			} else {
				sendPacket(ActionFailed.STATIC_PACKET);
			}
			return;
		}
		
		if (queueLock.isLocked()) {
			return;
		}
		
		try
		{
			if (state == GameClientState.CONNECTED) {
				if (getStats().processedPackets > 3) {
					if (Config.PACKET_HANDLER_DEBUG) {
						log.severe("Client " + toString() + " - Disconnected, too many packets in non-authed state.");
					}
					closeNow();
					return;
				}
				// TODO ThreadPoolManager.getInstance().executeIOPacket(this);
			}
			else
			{
				// TODO ThreadPoolManager.getInstance().executePacket(this);
			}
		}
		catch (RejectedExecutionException e)
		{
			// if the server is shutdown we ignore
			// TODO
		}
	}
	
	public ClientStats getStats(){
		return stats;
	}
	
}