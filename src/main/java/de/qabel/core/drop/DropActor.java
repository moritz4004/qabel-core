package de.qabel.core.drop;

import java.io.Serializable;
import java.net.URL;
import java.security.SecureRandom;
import java.util.*;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import de.qabel.ackack.MessageInfo;
import de.qabel.ackack.event.*;
import de.qabel.core.config.*;
import de.qabel.core.crypto.*;
import de.qabel.core.exceptions.QblDropInvalidMessageSizeException;
import de.qabel.core.exceptions.QblDropPayloadSizeException;
import de.qabel.core.exceptions.QblSpoofedSenderException;
import de.qabel.core.exceptions.QblVersionMismatchException;
import de.qabel.core.http.DropHTTP;
import de.qabel.core.http.HTTPResult;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

public class DropActor extends EventActor implements de.qabel.ackack.event.EventListener {
	private final static Logger logger = LogManager.getLogger(DropActor.class.getName());

	public static final String EVENT_DROP_MESSAGE_RECEIVED = "dropMessageReceived";
	private static final String EVENT_ACTION_DROP_MESSAGE_SEND = "sendDropMessage";
	private static final String PRIVATE_TYPE_MESSAGE_INPUT = "MessageInput";
	private final EventEmitter emitter;
	private DropServers mDropServers;
	private Identities mIdentities;
	private Contacts mContacts;
	GsonBuilder gb;
	Gson gson;
	ReceiverThread receiver;
	private long interval = 1000L;

	public void setInterval(long interval) {
		if(interval < 0)
			throw new IllegalArgumentException("interval must be greater equal 0");
		this.interval = interval;
	}

	public long getInterval() {
		return interval;
	}

	public DropActor(EventEmitter emitter) {
		super(emitter);
		this.emitter = emitter;
		gb = new GsonBuilder();
		gb.registerTypeAdapter(DropMessage.class, new DropSerializer());
		gb.registerTypeAdapter(DropMessage.class, new DropDeserializer());
		gson = gb.create();
		on(EVENT_ACTION_DROP_MESSAGE_SEND, this);
		// registerModelObject events
	}

	/**
	 * sends a DropMessage to a Set of Contacts
	 *
	 * @param emitter  EventEmitter to be used (EventEmitter.getDefault() if unsure)
	 * @param message  DropMessage to be send
	 * @param contacts Receiver of the message
	 */
	public static <T extends Serializable & Collection<Contact>> void send(EventEmitter emitter, DropMessage<? extends ModelObject> message, T contacts) {
		int nbr;
		if ((nbr = emitter.emit(EVENT_ACTION_DROP_MESSAGE_SEND, message, contacts)) != 1) {
			throw new RuntimeException("EVENT_ACTION_DROP_MESSAGE_SEND should only listened by one Listener (listener count = " + nbr + ")");
		}
	}

	/**
	 * sends a DropMessage to a Contact
	 *
	 * @param emitter EventEmitter to be used (EventEmitter.getDefault() if unsure)
	 * @param message DropMessage to be send
	 * @param contact Receiver of the message
	 */
	public static void send(EventEmitter emitter, DropMessage<? extends ModelObject> message, Contact contact) {
		ArrayList<Contact> contacts = new ArrayList<>(1);
		contacts.add(contact);
		send(emitter, message, contacts);
	}

	/**
	 * sends a ModelObject to a Contact via Drop
	 *
	 * @param emitter EventEmitter to be used (EventEmitter.getDefault() if unsure)
	 * @param message ModelObject to be send
	 * @param contact Receiver of the message
	 */
	public static void send(EventEmitter emitter, ModelObject message, Contact contact) {
		ArrayList<Contact> contacts = new ArrayList<>(1);
		contacts.add(contact);
		DropMessage<ModelObject> dm = new DropMessage<>(contact.getContactOwner(), message);
		send(emitter, dm, contacts);
	}

	/**
	 * Handles a received DropMessage. Puts this DropMessage into the registered
	 * Queues.
	 *
	 * @param dm DropMessage which should be handled
	 */
	private void handleDrop(DropMessage<? extends ModelObject> dm) {
		Class<? extends ModelObject> cls = dm.getModelObject();

		emitter.emit("dropMessage", dm);
	}

	@Override
	public void run() {
		startRetriever();
		super.run();
		try {
			stopRetriever();
		} catch (InterruptedException e) {
			// TODO
			e.printStackTrace();
		}
	}

	private void startRetriever() {
		receiver = new ReceiverThread();
		receiver.start();
	}

	private void stopRetriever() throws InterruptedException {
		receiver.shutdown();
		receiver.join();
	}

	/**
	 * retrieves new DropMessages from server and emits EVENT_DROP_MESSAGE_RECEIVED event.
	 */
	private void retrieve() {
		HashSet<DropServer> servers = new HashSet<DropServer>(getDropServers()
				.getDropServers());
		for (DropServer server : servers) {
			Collection<DropMessage<?>> results = this
					.retrieve(server.getUrl(), getIdentities().getIdentities(),
							getContacts().getContacts());
			MessageInfo mi = new MessageInfo();
			mi.setType(PRIVATE_TYPE_MESSAGE_INPUT);
			for (DropMessage<? extends ModelObject> dm : results) {
				emitter.emit(EVENT_DROP_MESSAGE_RECEIVED, dm);
			}
		}
	}

	public DropServers getDropServers() {
		return mDropServers;
	}

	public void setDropServers(DropServers mDropServers) {
		this.mDropServers = mDropServers;
	}

	public Identities getIdentities() {
		return mIdentities;
	}

	public void setIdentities(Identities identities) {
		this.mIdentities = identities;
	}

	public Contacts getContacts() {
		return mContacts;
	}

	public void setContacts(Contacts mContacts) {
		this.mContacts = mContacts;
	}


	/**
	 * Sends the message and waits for acknowledgement.
	 * Uses sendAndForget() for now.
	 * <p/>
	 * TODO: implement
	 *
	 * @param message  Message to send
	 * @param contacts Contacts to send message to
	 * @return DropResult which tell you the state of the sending
	 * @throws QblDropPayloadSizeException
	 */
	private DropResult send(DropMessage<? extends ModelObject> message, Collection<Contact> contacts) throws QblDropPayloadSizeException {
		return sendAndForget(message, contacts);
	}

	/**
	 * Sends the message to a collection of contacts and does not wait for acknowledgement
	 *
	 * @param message  Message to send
	 * @param contacts Contacts to send message to
	 * @return DropResult which tell you the state of the sending
	 * @throws QblDropPayloadSizeException
	 */
	private <T extends ModelObject> DropResult sendAndForget(DropMessage<T> message, Collection<Contact> contacts) throws QblDropPayloadSizeException {
		DropResult result = new DropResult();

		for (Contact contact : contacts) {
			result.addContactResult(this.sendAndForget(message, contact));
		}

		return result;
	}

	/**
	 * Sends the object to one contact and does not wait for acknowledgement
	 *
	 * @param object  Object to send
	 * @param contact Contact to send message to
	 * @return DropResultContact which tell you the state of the sending
	 * @throws QblDropPayloadSizeException
	 */
	private <T extends ModelObject> DropResultContact sendAndForget(T object, Contact contact) throws QblDropPayloadSizeException {
		DropHTTP http = new DropHTTP();

		DropMessage<T> dm = new DropMessage<T>(contact.getContactOwner(), object);

		return sendAndForget(dm, contact);
	}

	/**
	 * Sends the message to one contact and does not wait for acknowledgement
	 *
	 * @param message Message to send
	 * @param contact Contact to send message to
	 * @return DropResultContact which tell you the state of the sending
	 * @throws QblDropPayloadSizeException
	 */
	private <T extends ModelObject> DropResultContact sendAndForget(DropMessage<T> message, Contact contact) throws QblDropPayloadSizeException {
		DropResultContact result = new DropResultContact(contact);
		DropHTTP http = new DropHTTP();

		BinaryDropMessageV0 binaryMessage = new BinaryDropMessageV0(message);
		for (DropURL u : contact.getDropUrls()) {
			HTTPResult<?> dropResult = http.send(u.getUrl(), binaryMessage.assembleMessageFor(contact));
			result.addErrorCode(dropResult.getResponseCode());
		}

		return result;
	}

	/**
	 * Retrieves a drop message from given URL
	 *
	 * @param url      URL where to retrieve the drop from
	 * @param identities Identities to decrypt message with
	 * @param contacts Contacts to check the signature with
	 * @return Retrieved, encrypted Dropmessages.
	 */
	public Collection<DropMessage<?>> retrieve(URL url, Collection<Identity> identities,
											   Collection<Contact> contacts) {
		DropHTTP http = new DropHTTP();
		HTTPResult<Collection<byte[]>> cipherMessages = http.receiveMessages(url);
		Collection<DropMessage<?>> plainMessages = new ArrayList<>();

		List<Contact> ccc = new ArrayList<Contact>(contacts);
		Collections.shuffle(ccc, new SecureRandom());

		for (byte[] cipherMessage : cipherMessages.getData()) {
			AbstractBinaryDropMessage binMessage;
			byte binaryFormatVersion = cipherMessage[0];

			switch (binaryFormatVersion) {
				case 0:
					try {
						binMessage = new BinaryDropMessageV0(cipherMessage);
					} catch (QblVersionMismatchException e) {
						logger.error("Version mismatch in binary drop message", e);
						throw new RuntimeException("Version mismatch should not happen", e);
					} catch (QblDropInvalidMessageSizeException e) {
						logger.info("Binary drop message version 0 with unexpected size discarded.");
						// Invalid message uploads may happen with malicious intent
						// or by broken clients. Skip.
						continue;
					}
					break;
				default:
					logger.warn("Unknown binary drop message version " + binaryFormatVersion);
					// cannot handle this message -> skip
					continue;
			}
			for (Identity identity : identities) {
				DropMessage<?> dropMessage = null;
				try {
					dropMessage = binMessage.disassembleMessage(identity);
				} catch (QblSpoofedSenderException e) {
					//TODO: Notify the user about the spoofed message
					break;
				}
				if (dropMessage != null) {
					for (Contact c : contacts) {
						if (c.getKeyIdentifier().equals(dropMessage.getSenderKeyId())){
							if (dropMessage.registerSender(c)){
								plainMessages.add(dropMessage);
								break;
							}
						}
					}
					break;
				}
			}
		}
		return plainMessages;
	}

	@Override
	public void onEvent(String event, MessageInfo info, Object... data) {
		if (EVENT_ACTION_DROP_MESSAGE_SEND.equals(event) == false) {
			return;
		}
		try {
			send((DropMessage<?>) data[0], (Collection) data[1]);
		} catch (QblDropPayloadSizeException e) {
			logger.warn("Failed to send message", e);
		}
	}

	private class ReceiverThread extends Thread {
		boolean run = true;
		@Override
		public void run() {
			try {
				while (run && isInterrupted() == false) {
					retrieve();
					Thread.sleep(interval);
				}
			} catch (InterruptedException e) {
				// Ignore interrupts.
			}
		}
		public void shutdown() {
			run = false;
			this.interrupt();
		}
	}
}
