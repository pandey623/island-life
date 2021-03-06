package com.friendlyblob.islandlife.server.actions;

public class GameActions {

	public static GameAction [] onPlayer = new GameAction[]{
		GameAction.ATTACK,
		GameAction.TRADE,
		GameAction.WHISPER
	};
	
	public static enum GameAction {
		ATTACK("Attack"),
		TRADE("Trade"),
		WHISPER("Whisper");
		
		private String title;
		
		private GameAction(String title) {
			this.title = title;
		}
	}
}
