package nl.tudelft.blockchain.scaleoutdistributedledger.model;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

import java.security.KeyPair;
import java.util.*;

import org.junit.Before;
import org.junit.Test;

import nl.tudelft.blockchain.scaleoutdistributedledger.Application;
import nl.tudelft.blockchain.scaleoutdistributedledger.LocalStore;
import nl.tudelft.blockchain.scaleoutdistributedledger.test.utils.TestHelper;

/**
 * Test class for {@link Proof}.
 */
public class ProofTest {
	private LocalStore storeSpy;
	private OwnNode ownNode;
	private Application appMock;
	private Block genesisBlock;
	
	/**
	 * @throws Exception - Will not occur.
	 */
	@Before
	public void setUp() throws Exception {
		//Create own node and generate keys
		ownNode = new OwnNode(0);
		KeyPair pair = Ed25519Key.generateKeys();
		ownNode.setPrivateKey(pair.getPrivate().getEncoded());
		ownNode.setPublicKey(pair.getPublic().getEncoded());
		
		genesisBlock = TestHelper.generateGenesis(ownNode, 3, 1000);
		Map<Integer, Node> nodes = TestHelper.getNodeList(genesisBlock);
		
		appMock = mock(Application.class);
		storeSpy = spy(new LocalStore(ownNode, appMock, genesisBlock, false));
		storeSpy.getNodes().putAll(nodes);
	}

	/**
	 * Creates the following scenario.
	 * <pre>
	 * 3: 0 --> 1, source = GENESIS 0
	 * 3: 1 --> 0, source = [3: 0 --> 1]
	 * 4: 0 --> 2, source = [3: 1 --> 0]
	 * </pre>
	 * @return - a transaction from node 0 to node 2
	 */
	public Transaction basicScenario() {
		Node node1 = storeSpy.getNode(1);
		Node node2 = storeSpy.getNode(2);
		Chain ownChain = ownNode.getChain();
		
		//3: 0 --> 1, source = GENESIS 0
		TreeSet<Transaction> source0to1 = new TreeSet<>();
		source0to1.add(ownChain.getGenesisTransaction());
		Transaction transaction0to1 = new Transaction(3, ownNode, node1, 100, 900, source0to1);
		Block block1node0 = ownChain.appendNewBlock();
		block1node0.addTransaction(transaction0to1);
		
		//3: 1 --> 0, source = [3: 0 --> 1]
		TreeSet<Transaction> source1to0 = new TreeSet<>();
		source1to0.add(transaction0to1);
		Transaction transaction1to0 = new Transaction(3, node1, ownNode, 100, 0, source1to0);
		Block block1node1 = new Block(genesisBlock, node1);
		block1node1.addTransaction(transaction1to0);
		node1.getChain().getBlocks().add(block1node1);
		
		//4: 0 --> 2, source = [3: 1 --> 0]
		TreeSet<Transaction> source0to2 = new TreeSet<>();
		source0to2.add(transaction1to0);
		Transaction transaction0to2 = new Transaction(4, ownNode, node2, 100, 0, source0to2);
		Block block2node0 = ownChain.appendNewBlock();
		block2node0.addTransaction(transaction0to2);
		
		//commit block 2 of node 0
		block2node0.commit(storeSpy);
		
		return transaction0to2;
	}
	
	/**
	 * Test method for {@link Proof#createProof}.
	 */
	@Test
	public void testCreateProof() {
		Node node1 = storeSpy.getNode(1);
		
		Chain ownChain = ownNode.getChain();
		Chain node1Chain = node1.getChain();
		
		Transaction transaction = basicScenario();
		
		Proof proof = Proof.createProof(storeSpy, transaction);
		
		//Proof should contain all the blocks of our own chain
		List<Block> ownNodeUpdates = proof.getChainUpdates().get(ownNode);
		assertEquals(ownChain.getBlocks(), ownNodeUpdates);
		
		//Node 1 should have genesis block and created block
		List<Block> node1Updates = proof.getChainUpdates().get(node1);
		assertEquals(node1Chain.getBlocks(), node1Updates);
	}
	
	/**
	 * Test method for {@link Proof#createProof}.
	 */
	@Test
	public void testCreateProof2() {
		Node node1 = storeSpy.getNode(1);
		Node node2 = storeSpy.getNode(2);
		Chain ownChain = ownNode.getChain();
		Chain node1Chain = node1.getChain();
		
		Transaction transaction = basicScenario();
		
		//Node 2 knows about the genesis of node 1 already
		node2.getMetaKnowledge().put(node1, 0);
		
		//send to node 2
		Proof proof = Proof.createProof(storeSpy, transaction);
		
		//Proof should contain the first 3 blocks of the ownNode
		List<Block> ownNodeUpdates = proof.getChainUpdates().get(ownNode);
		assertEquals(ownChain.getBlocks(), ownNodeUpdates);
		
		//Node 1 should have only the created block
		List<Block> node1Updates = proof.getChainUpdates().get(node1);
		List<Block> expectedNode1Updates = Arrays.asList(node1Chain.getLastBlock());
		assertEquals(expectedNode1Updates, node1Updates);
	}
	
	/**
	 * Test method for {@link Proof#createProof}.
	 */
	@Test
	public void testCreateProof3() {
		Node node1 = storeSpy.getNode(1);
		Node node2 = storeSpy.getNode(2);
		Chain ownChain = ownNode.getChain();
		
		Transaction transaction = basicScenario();
		
		//Node 2 knows about block 1 of node 0 and of block 1 of node 1
		node2.getMetaKnowledge().put(ownNode, 1);
		node2.getMetaKnowledge().put(node1, 1);
		
		//send to node 2
		Proof proof = Proof.createProof(storeSpy, transaction);
		
		//Proof should contain only the last block of node 0
		List<Block> ownNodeUpdates = proof.getChainUpdates().get(ownNode);
		List<Block> expectedOwnNodeUpdates = Arrays.asList(ownChain.getLastBlock());
		assertEquals(expectedOwnNodeUpdates, ownNodeUpdates);
		
		//No blocks of node1 should be included
		assertFalse(proof.getChainUpdates().containsKey(node1));
	}

}
