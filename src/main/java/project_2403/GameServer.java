package project_2403;

import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;

/**
 * 멀티플레이 게임 서버
 * 방 생성/참가/빠른 참가를 관리하고 게임 진행을 중계합니다.
 */
public class GameServer {
    // Render 환경변수 PORT 사용, 없으면 12345
    private static final int PORT = Integer.parseInt(System.getenv().getOrDefault("PORT", "12345"));
    private static final int MAX_ROOMS = 10;

    // roomCode -> RoomInfo
    private static final Map<String, RoomInfo> rooms = new ConcurrentHashMap<>();
    // 빠른 참가 대기열 (2인용, 3인용 분리)
    private static final Queue<ClientHandler> quickQueue2 = new ConcurrentLinkedQueue<>();
    private static final Queue<ClientHandler> quickQueue3 = new ConcurrentLinkedQueue<>();

    public static void main(String[] args) throws IOException {
        ServerSocket serverSocket = new ServerSocket(PORT);
        System.out.println("[서버] 레인보우 홀덤 서버 시작 - 포트: " + PORT);

        while (true) {
            Socket clientSocket = serverSocket.accept();
            ClientHandler handler = new ClientHandler(clientSocket);
            new Thread(handler).start();
        }
    }

    // ──────────────────────────────────────────────
    // 방 관련 유틸
    // ──────────────────────────────────────────────

    /** 6자리 숫자 방 코드 생성 */
    static String generateRoomCode() {
        Random rng = new Random();
        String code;
        do {
            code = String.format("%06d", rng.nextInt(1000000));
        } while (rooms.containsKey(code));
        return code;
    }

    static RoomInfo getRoom(String code) {
        return rooms.get(code);
    }

    static void removeRoom(String code) {
        rooms.remove(code);
    }

    /** 빠른 참가: 적절한 대기열에 추가하고 방이 꽉 차면 게임 시작 */
    static synchronized void joinQuickQueue(ClientHandler handler, int maxPlayers) {
        Queue<ClientHandler> queue = (maxPlayers == 2) ? quickQueue2 : quickQueue3;
        queue.add(handler);
        handler.sendMessage("QUEUE_JOINED|" + maxPlayers);

        // 대기열이 꽉 차면 방을 만들어 게임 시작
        if (queue.size() >= maxPlayers) {
            String code = generateRoomCode();
            RoomInfo room = new RoomInfo(code, "빠른참가방", maxPlayers, null);
            rooms.put(code, room);

            for (int i = 0; i < maxPlayers; i++) {
                ClientHandler ch = queue.poll();
                if (ch != null) {
                    room.addPlayer(ch);
                }
            }
            room.broadcastRoomState();
            room.startGameIfReady();
        }
    }

    // ──────────────────────────────────────────────
    // 내부 클래스: RoomInfo
    // ──────────────────────────────────────────────
    static class RoomInfo {
        final String code;
        final String roomName;
        final int maxPlayers;
        final String password; // null이면 공개방
        final List<ClientHandler> players = new ArrayList<>();
        boolean gameStarted = false;

        // 게임 상태
        GameState gameState;

        RoomInfo(String code, String roomName, int maxPlayers, String password) {
            this.code = code;
            this.roomName = roomName;
            this.maxPlayers = maxPlayers;
            this.password = password;
        }

        synchronized void addPlayer(ClientHandler ch) {
            players.add(ch);
            ch.currentRoom = this;
            ch.playerIndex = players.size() - 1;
        }

        synchronized void removePlayer(ClientHandler ch) {
            players.remove(ch);
            ch.currentRoom = null;
            if (gameStarted) {
                broadcast("PLAYER_LEFT|" + ch.playerName);
            }
        }

        /** 현재 방 상태를 모든 플레이어에게 전송 */
        void broadcastRoomState() {
            StringBuilder sb = new StringBuilder("ROOM_STATE");
            sb.append("|").append(code);
            sb.append("|").append(roomName);
            sb.append("|").append(maxPlayers);
            sb.append("|").append(players.size());
            for (ClientHandler ch : players) {
                sb.append("|").append(ch.playerName);
            }
            broadcast(sb.toString());
        }

        void broadcast(String msg) {
            for (ClientHandler ch : players) {
                ch.sendMessage(msg);
            }
        }

        void startGameIfReady() {
            if (players.size() == maxPlayers && !gameStarted) {
                gameStarted = true;
                gameState = new GameState(this);
                broadcast("GAME_START|" + maxPlayers);
                gameState.initializeAndDeal();
            }
        }
    }

    // ──────────────────────────────────────────────
    // 내부 클래스: GameState (서버 측 게임 로직)
    // ──────────────────────────────────────────────
    static class GameState {
        final RoomInfo room;
        final List<String> playerNames = new ArrayList<>();
        final List<int[]> personalCards = new ArrayList<>();
        final List<Integer> sharedCards = new ArrayList<>();
        final List<Boolean> folded = new ArrayList<>();
        final List<Integer> chips = new ArrayList<>();
        final List<Integer> currentBets = new ArrayList<>();
        final List<List<Integer>> revealedIndices = new ArrayList<>();

        int pot = 0;
        int round = 1;
        int currentRoundBet = 0;
        int bettingPlayerIndex = -1;
        boolean bettingPhase = false;

        static final int INITIAL_CHIPS = 100;
        static final int MIN_BET = 10;

        GameState(RoomInfo room) {
            this.room = room;
            for (ClientHandler ch : room.players) {
                playerNames.add(ch.playerName);
                chips.add(INITIAL_CHIPS);
                folded.add(false);
                currentBets.add(0);
                revealedIndices.add(new ArrayList<>());
            }
        }

        void initializeAndDeal() {
            List<Integer> deck = new ArrayList<>();
            for (int v = 1; v <= 10; v++) {
                for (int i = 0; i < v; i++) deck.add(v);
            }
            Collections.shuffle(deck);

            int n = playerNames.size();
            for (int i = 0; i < n; i++) {
                personalCards.add(new int[]{deck.remove(0), deck.remove(0), deck.remove(0)});
            }
            for (int i = 0; i < 4; i++) {
                sharedCards.add(deck.remove(0));
            }

            for (int i = 0; i < n; i++) {
                int[] pc = personalCards.get(i);
                room.players.get(i).sendMessage(
                    "YOUR_CARDS|" + pc[0] + "," + pc[1] + "," + pc[2]
                );
            }

            room.broadcast("SHARED_CARD_COUNT|4");
            room.broadcast("STATUS|카드를 한 장 선택하고 공개 버튼을 누르세요!");
            room.broadcast("ROUND|1");
            requestReveal();
        }

        void requestReveal() {
            room.broadcast("REQUEST_REVEAL|1라운드: 공개할 카드를 선택하세요.");
        }

        synchronized void playerReveal(int playerIdx, int cardIndex) {
            List<Integer> ri = revealedIndices.get(playerIdx);
            if (ri.contains(cardIndex)) return;
            ri.add(cardIndex);

            int cardValue = personalCards.get(playerIdx)[cardIndex];
            room.broadcast("REVEALED|" + playerIdx + "|" + cardIndex + "|" + cardValue + "|" + playerNames.get(playerIdx));

            boolean allRevealed = true;
            for (int i = 0; i < room.players.size(); i++) {
                if (!folded.get(i) && revealedIndices.get(i).isEmpty()) {
                    allRevealed = false;
                    break;
                }
            }

            if (allRevealed) {
                startBettingRound();
            }
        }

        void startBettingRound() {
            currentRoundBet = 0;
            for (int i = 0; i < currentBets.size(); i++) currentBets.set(i, 0);

            bettingPlayerIndex = determineFirstBettor();
            bettingPhase = true;

            room.broadcast("BETTING_START|" + bettingPlayerIndex);
            notifyCurrentBettor();
        }

        int determineFirstBettor() {
            int minVal = Integer.MAX_VALUE, minIdx = 0;
            for (int i = 0; i < room.players.size(); i++) {
                if (folded.get(i)) continue;
                List<Integer> ri = revealedIndices.get(i);
                if (ri.isEmpty()) continue;
                int val = personalCards.get(i)[ri.get(0)];
                if (val < minVal) { minVal = val; minIdx = i; }
            }
            return minIdx;
        }

        void notifyCurrentBettor() {
            room.broadcast("YOUR_TURN|" + bettingPlayerIndex + "|" + currentRoundBet + "|" + pot);
            room.players.get(bettingPlayerIndex).sendMessage("MUST_BET|" + MIN_BET + "|" + currentRoundBet);
        }

        synchronized void playerBet(int playerIdx, String action) {
            if (playerIdx != bettingPlayerIndex || !bettingPhase) return;

            if (action.equals("FOLD")) {
                folded.set(playerIdx, true);
                room.broadcast("PLAYER_ACTION|" + playerIdx + "|FOLD|" + playerNames.get(playerIdx));
            } else {
                int amount = Math.max(MIN_BET, currentRoundBet);
                int available = chips.get(playerIdx);
                int actualBet = Math.min(amount, available);
                chips.set(playerIdx, available - actualBet);
                currentBets.set(playerIdx, currentBets.get(playerIdx) + actualBet);
                pot += actualBet;
                if (actualBet > currentRoundBet) currentRoundBet = actualBet;

                room.broadcast("PLAYER_ACTION|" + playerIdx + "|CALL|" + playerNames.get(playerIdx) + "|" + actualBet + "|" + pot);
            }

            if (getActivePlayers() <= 1 || isBettingRoundFinished()) {
                bettingPhase = false;
                advanceRound();
            } else {
                bettingPlayerIndex = nextActiveBettor();
                notifyCurrentBettor();
            }
        }

        int nextActiveBettor() {
            int n = room.players.size();
            int next = (bettingPlayerIndex + 1) % n;
            while (folded.get(next)) next = (next + 1) % n;
            return next;
        }

        boolean isBettingRoundFinished() {
            for (int i = 0; i < room.players.size(); i++) {
                if (!folded.get(i) && currentBets.get(i) < currentRoundBet) return false;
            }
            return true;
        }

        int getActivePlayers() {
            int count = 0;
            for (boolean f : folded) if (!f) count++;
            return count;
        }

        void advanceRound() {
            if (getActivePlayers() <= 1) {
                endGame();
                return;
            }

            round++;
            if (round > 4) {
                endGame();
                return;
            }

            int sharedCard = sharedCards.get(round - 1);
            room.broadcast("SHARED_REVEALED|" + (round - 1) + "|" + sharedCard + "|" + round);
            room.broadcast("ROUND|" + round);

            if (round <= 4) {
                startBettingRound();
            }
        }

        int calculateScore(int playerIdx) {
            Map<Integer, Integer> counts = new HashMap<>();
            for (int v : sharedCards) counts.merge(v, 1, Integer::sum);
            for (int v : personalCards.get(playerIdx)) counts.merge(v, 1, Integer::sum);

            int score = 0;
            for (int v : sharedCards) if (counts.get(v) == 1) score += v;
            for (int v : personalCards.get(playerIdx)) if (counts.get(v) == 1) score += v;
            return score;
        }

        void endGame() {
            List<Integer> active = new ArrayList<>();
            for (int i = 0; i < room.players.size(); i++) {
                if (!folded.get(i)) active.add(i);
            }

            int winnerIdx = -1;
            int minScore = Integer.MAX_VALUE;

            if (active.size() == 1) {
                winnerIdx = active.get(0);
            } else {
                for (int idx : active) {
                    int s = calculateScore(idx);
                    if (s < minScore) { minScore = s; winnerIdx = idx; }
                }
            }

            if (winnerIdx >= 0) {
                chips.set(winnerIdx, chips.get(winnerIdx) + pot);
            }

            StringBuilder sb = new StringBuilder("GAME_RESULT");
            sb.append("|").append(winnerIdx);
            sb.append("|").append(pot);
            for (int i = 0; i < room.players.size(); i++) {
                int score = folded.get(i) ? -1 : calculateScore(i);
                sb.append("|").append(i).append(",").append(score).append(",").append(chips.get(i));
            }
            room.broadcast(sb.toString());
            rooms.remove(room.code);
        }
    }

    // ──────────────────────────────────────────────
    // 내부 클래스: ClientHandler
    // ──────────────────────────────────────────────
    static class ClientHandler implements Runnable {
        final Socket socket;
        PrintWriter out;
        BufferedReader in;

        String playerName = "Unknown";
        RoomInfo currentRoom = null;
        int playerIndex = -1;

        ClientHandler(Socket socket) {
            this.socket = socket;
        }

        void sendMessage(String msg) {
            if (out != null) out.println(msg);
        }

        @Override
        public void run() {
            try {
                out = new PrintWriter(new OutputStreamWriter(socket.getOutputStream(), "UTF-8"), true);
                in = new BufferedReader(new InputStreamReader(socket.getInputStream(), "UTF-8"));

                String line;
                while ((line = in.readLine()) != null) {
                    handleMessage(line.trim());
                }
            } catch (IOException e) {
                System.out.println("[서버] 클라이언트 연결 종료: " + playerName);
            } finally {
                if (currentRoom != null) {
                    currentRoom.removePlayer(this);
                }
                try { socket.close(); } catch (IOException ignored) {}
            }
        }

        void handleMessage(String msg) {
            String[] parts = msg.split("\\|");
            String cmd = parts[0];

            switch (cmd) {
                case "LOGIN":
                    playerName = parts[1];
                    sendMessage("LOGIN_OK|" + playerName);
                    System.out.println("[서버] 접속: " + playerName);
                    break;

                case "CREATE_ROOM":
                    handleCreateRoom(parts);
                    break;

                case "JOIN_ROOM":
                    handleJoinRoom(parts);
                    break;

                case "QUICK_JOIN":
                    int qMax = Integer.parseInt(parts[1]);
                    joinQuickQueue(this, qMax);
                    break;

                case "ROOM_LIST":
                    sendRoomList();
                    break;

                case "REVEAL_CARD":
                    if (currentRoom != null && currentRoom.gameState != null) {
                        currentRoom.gameState.playerReveal(playerIndex, Integer.parseInt(parts[1]));
                    }
                    break;

                case "BET_ACTION":
                    if (currentRoom != null && currentRoom.gameState != null) {
                        currentRoom.gameState.playerBet(playerIndex, parts[1]);
                    }
                    break;

                case "LEAVE_ROOM":
                    if (currentRoom != null) currentRoom.removePlayer(this);
                    break;
            }
        }

        void handleCreateRoom(String[] parts) {
            String name = parts[1];
            int max = Integer.parseInt(parts[2]);
            String pw = (parts.length > 3 && !parts[3].isEmpty()) ? parts[3] : null;

            String code = generateRoomCode();
            RoomInfo room = new RoomInfo(code, name, max, pw);
            rooms.put(code, room);
            room.addPlayer(this);

            sendMessage("ROOM_CREATED|" + code + "|" + name + "|" + max);
            room.broadcastRoomState();
        }

        void handleJoinRoom(String[] parts) {
            String code = parts[1];
            String pw = (parts.length > 2) ? parts[2] : "";

            RoomInfo room = rooms.get(code);
            if (room == null) {
                sendMessage("JOIN_FAIL|존재하지 않는 방입니다.");
                return;
            }
            if (room.gameStarted) {
                sendMessage("JOIN_FAIL|이미 게임이 시작된 방입니다.");
                return;
            }
            if (room.players.size() >= room.maxPlayers) {
                sendMessage("JOIN_FAIL|방이 가득 찼습니다.");
                return;
            }
            if (room.password != null && !room.password.equals(pw)) {
                sendMessage("JOIN_FAIL|비밀번호가 올바르지 않습니다.");
                return;
            }

            room.addPlayer(this);
            sendMessage("JOIN_OK|" + code + "|" + room.roomName);
            room.broadcastRoomState();
            room.startGameIfReady();
        }

        void sendRoomList() {
            StringBuilder sb = new StringBuilder("ROOM_LIST");
            for (RoomInfo r : rooms.values()) {
                if (!r.gameStarted && r.players.size() < r.maxPlayers) {
                    sb.append("|")
                      .append(r.code).append(",")
                      .append(r.roomName).append(",")
                      .append(r.players.size()).append("/").append(r.maxPlayers).append(",")
                      .append(r.password != null ? "비공개" : "공개");
                }
            }
            sendMessage(sb.toString());
        }
    }
}
