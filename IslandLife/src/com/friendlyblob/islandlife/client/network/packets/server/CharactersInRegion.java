package com.friendlyblob.islandlife.client.network.packets.server;

import com.friendlyblob.islandlife.client.entities.GameCharacter;
import com.friendlyblob.islandlife.client.gameworld.GameWorld;
import com.friendlyblob.islandlife.client.network.packets.ReceivablePacket;

public class CharactersInRegion extends ReceivablePacket {

	int characterCount;
	
	@Override
	public boolean read() {
		characterCount = readH();
		
		GameWorld world = GameWorld.getInstance();
		
		int playerId = world.getPlayer().objectId;
		
		try {
			for (int i = 0; i < characterCount; i++) {
				int objectId = readD();
				int x = readD();
				int y = readD();
				int speed = readD();
				
				// TODO player id doesn't work
				if(playerId == objectId) {
					continue;
				}
				
				if (world.characterExists(objectId)) {
					// TODO some sort of interpolation
					// world.getCharacter(objectId).moveTo(x, y, speed);
				} else {
					world.putCharacter(new GameCharacter(objectId, x, y));
				}
			}
		} catch (Exception e) {
			System.out.println(e.getMessage());
		}
		
		
		return false;
	}

	@Override
	public void run() {
		
	}

}
