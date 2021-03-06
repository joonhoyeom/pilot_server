import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.StandardSocketOptions;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.charset.Charset;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.Vector;

import message.Command;
import message.MessageHeader;
import messageResponder.MessageResponder;
import utils.Utils;

public class Communicator {
	
	//shared variable
	public List<Client> clientList;
	public Object clientListMutex;
	private boolean stop = false;
		
	private SocketHandler socketHandler;
	private MessageHandler messageHandler;
		
	class SocketHandler extends Thread{
		
		final private int PORT = 9999;
	
		private Selector selector;
		private ServerSocketChannel serverSocketChannel = null;
	
		public SocketHandler() {
			
			//Selector and server socket initialize
			setPriority(8);
			try {
				selector = Selector.open();

				serverSocketChannel = ServerSocketChannel.open();
				serverSocketChannel.setOption(StandardSocketOptions.SO_REUSEADDR, true);
				serverSocketChannel.configureBlocking(false);
				serverSocketChannel.bind(new InetSocketAddress(PORT));
				serverSocketChannel.register(selector, SelectionKey.OP_ACCEPT);
			} catch (IOException e) {
				e.printStackTrace();
				System.exit(0);
			}
		}
		
		@Override
		public void run() {
			
			// multiplexing request
			while (!stop) {
				int nSelectedKey = 0;
				try {
					nSelectedKey = selector.select(1500);
				} catch (IOException e) {
					e.printStackTrace();
					break;
				}

				if(nSelectedKey > 0){
					Set<SelectionKey> readyKeys = selector.selectedKeys();
					Iterator<SelectionKey> iterator = readyKeys.iterator();

					// Handle selected sockets
					while (iterator.hasNext()) {
						SelectionKey key = iterator.next();
						iterator.remove();
						try {
							if (key.isAcceptable()) {
								SocketChannel socketChannel = ((ServerSocketChannel) key.channel()).accept();
							
								Client client = new Client( socketChannel.getRemoteAddress().toString().substring(1).split(":")[0], socketChannel);
								client.pushMessage(Command.DIR, "".getBytes());
								
								synchronized (clientListMutex) {
									clientList.add(client);
								}
							
								socketChannel.configureBlocking(false);
								SelectionKey selectionKey = socketChannel.register(selector, SelectionKey.OP_WRITE | SelectionKey.OP_READ);
								selectionKey.attach(client);
														
								System.out.println("[" + client.getIP() + "]connected");
				
							
							} else if (key.isReadable()) {
								System.out.println("###DEBUG key : READABLE");
							
								Client client = (Client)key.attachment();
							
								int recvCount = 0;							
								synchronized (client.recvBufferMutex) {
									//TODO recvBuffer overflow handling
									recvCount= client.readFromSocket();
//									if(recvCount > 0){
//										System.out.println(recvCount + " : "+ new String(client.recvBuffer.array()));
//									}
								}
								//client closes socket.
								if(recvCount == -1){
									synchronized (clientListMutex) {
										clientList.remove(client);	
									}
									//remove from selector set
									key.cancel();
									if(client.socketChannel.isOpen())
										client.socketChannel.close();
									System.out.println("[" + client.getIP() +"] : closed" );
								}							
							} else if (key.isWritable()) {
								//System.out.println("###DEBUG key : WRITABLE");
								Client client = (Client)key.attachment();
							
								int writeCount = 0;
								synchronized (client.sendBufferMutex) {
									if(client.sendBuffer.position() > 0){
										client.sendBuffer.flip();
										writeCount = client.writeToSocket();
									}
								}
								key.interestOps(SelectionKey.OP_READ);
							} else {
								System.out.println("Unknown Key Behavior");
							}
						} catch (IOException e) {
							// TODO Auto-generated catch block
							e.printStackTrace();
						}
					}
				}
				//if there are messages to send, set writable option
				synchronized (clientListMutex){
					for(Client i : clientList){
						synchronized (i.sendBufferMutex){
							if(i.sendBuffer.position() > 0){
								i.socketChannel.keyFor(selector).interestOps(SelectionKey.OP_WRITE | SelectionKey.OP_READ);
							}
						}
					}
				}
			}

			if (serverSocketChannel.isOpen()) {
				try {
					serverSocketChannel.close();
				} catch (IOException e) {
					System.err.println("Socket close fail");
				}
			}
		}
	}
	
	class MessageHandler extends Thread{
		
		//parse recvBuffer into messages
		private void processMessages(Client client){
			setPriority(3);
			byte[] recvBufferCopy = null;
			ByteBuffer recvBuffer = client.recvBuffer;

			//TODO reduce critical section
			//fetch messages from recvBuffer, and process each message
			synchronized (client.recvBufferMutex) {
								
				if(recvBuffer.position() > 0){

					//copy data in recvBuffer
					recvBufferCopy = new byte[recvBuffer.position()];
					System.arraycopy(recvBuffer.array(), 0, recvBufferCopy, 0, recvBufferCopy.length);
					recvBuffer.clear();
					
					int i = 0;
					while(i < recvBufferCopy.length){
						//Find position of first messageStart
						int headerPos = Utils.indexOf(recvBufferCopy, MessageHeader.messageStart, i);
						
						if(headerPos == -1){
							System.out.println("No MessageStart");
							System.out.println("[" + (recvBufferCopy.length - i) + "] : recvBufferCopy dump");
							Utils.printByteArray(recvBufferCopy, i);
							break;
						}
						
						//Header is not arrived yet
						if (recvBufferCopy.length - headerPos < MessageHeader.serializedSize) {
							System.out.println("Invalid Header");
							// put last header into buffer back
							recvBuffer.put(recvBufferCopy, headerPos, recvBufferCopy.length - headerPos);
							break;
						}
						//Unserialize MessageHeader
						MessageHeader header = new MessageHeader(recvBufferCopy, headerPos);
						int messageBodyStart = headerPos + MessageHeader.serializedSize;
						int messageBodyEnd = headerPos + MessageHeader.serializedSize + header.getMessageBodySize();

						//TODO large message handle -> break message into several pieces.
						//MessageBody is not arrived yet
						if(messageBodyEnd > recvBufferCopy.length){
							recvBuffer.put(recvBufferCopy, headerPos, recvBufferCopy.length - headerPos);
							break;
						}
						
						byte []messageBody = new byte [header.getMessageBodySize()];
						
						System.arraycopy(recvBufferCopy, messageBodyStart, messageBody, 0, header.getMessageBodySize());
						
						//Process MessageBody
						{
							MessageResponder mr = MessageResponder.newMessageResponder(header);
							if(mr != null){
								mr.respond(messageBody);
							} else {
								System.err.println("Invalid message header");
							}							
						}
						i = messageBodyEnd;
					}
				}
				else //there are no messages
					return;
			}
				
		}
				
		
		@Override
		public void run() {
			while( !stop ){
				synchronized (clientListMutex) {
					for(Client client : clientList){
						processMessages(client);	
					}	
				}
				
			}
		}
	}	
		
	public Communicator(){
		clientList = new Vector<Client>();
		clientListMutex = new Object();
		socketHandler = new SocketHandler();
		messageHandler = new MessageHandler();
	}
	
	public void communicatorRun() {		
		socketHandler.start();
		messageHandler.start();
	}

	public void setStop() {
		stop = true;
	}
	
	public boolean isAlive(){
		return ( socketHandler != null && socketHandler.isAlive() ) || ( messageHandler != null && messageHandler.isAlive() );
	}
	
	public void interrupt(){
		
		if(socketHandler != null)
			socketHandler.interrupt();
		
		if(messageHandler != null)
			messageHandler.interrupt();	
	
	}
	
	public void join() throws InterruptedException {
		
		if(socketHandler != null)
			socketHandler.join();
			
		if(messageHandler != null)
			messageHandler.join();		
	
	}
}
