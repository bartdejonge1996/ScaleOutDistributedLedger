import express from 'express';
const router = express.Router();
import app from '../app';
import NodeList from '../model/NodeList';
const sseMW = require('./../helpers/sse');
import Transaction from '../model/Transaction';
import TransactionList from '../model/TransactionList';

/**
 * Gets all nodes.
 */
router.get('/', (req, res) => {
	res.json({
		nodes: app.nodeList.getNodes()
	});
});

/**
 * Updates a certain node. Body should contain id, address, port and publicKey.
 */
router.post('/update-node', (req, res) => {
	if(!isPresent(req.body.id) || !isPresent(req.body.address) || !isPresent(req.body.port) || !isPresent(req.body.publicKey)) {
		res.status(403);
		res.json({success: false, err: 'Specify id, address, port and publicKey'});
	} else {
		if (app.nodeList.updateNode(req.body.id, req.body.address, req.body.port, req.body.publicKey)) {
			res.json({success: true});
		} else {
			res.status(403);
			res.json({success: false, err: 'invalid node'});
		}
	}
});

/**
 * Register a new node, body should contain address, port and publicKey.
 */
router.post('/register-node', (req, res) => {
    if(!isPresent(req.body.address) || !isPresent(req.body.port) || !isPresent(req.body.publicKey) || !isPresent(req.body.id)) {
        res.status(403);
        res.json({success: false, err: 'Specify id, address, port and publicKey'});
    } else {
        const id = app.nodeList.registerNode(req.body.id, req.body.address, req.body.port, req.body.publicKey);
        updateSseClients();
        res.json({success: true, id: id});
    }
});

router.post('/register-transaction', (req, res) => {
	if(!isPresent(req.body.from) || !isPresent(req.body.to) || !isPresent(req.body.amount)|| !isPresent(req.body.remainder)|| !isPresent(req.body.numberOfChains) || !isPresent(req.body.numberOfBlocks)) {
		res.status(403);
		res.json({success: false, err: 'Specify from, to, amount, remainder, remainder, numberOfChains and numberOfBlocks'});
	} else {
		const tx = new Transaction(req.body.from, req.body.to, req.body.amount, req.body.remainder, req.body.numberOfChains, req.body.numberOfBlocks);
		app.transactionList.addTransaction(tx);
		updateSseClients();
        res.json({success: true});
	}
});

/**
 * Update the running status of a node.
 */
router.post('/set-node-status', (req, res) => {
    if(!isPresent(req.body.id) || !isPresent(req.body.running)) {
        res.status(403);
        res.json({success: false, err: 'Specify node ID and running status'});
    } else {
        app.nodeList.setNodeStatus(req.body.id, req.body.running);
        res.json({success: true, id: req.body.id});
    }
});

/**
 * Gets a specific node. Getter body should contain id.
 */
router.get('/node', (req, res) => {
	if(!isPresent(req.body.id)) {
		res.status(403);
		res.json({success: false, err: 'Specify id'});
	} else {
		const node = app.nodeList.getNode(req.body.id);
		if (node == null) {
			res.status(403);
			res.json({success: false, err: 'invalid id or uninitialized node'});
		} else {
			res.json({success: true, node: node});
		}
	}
});

router.get('/demo', (req, res) => {
	res.render('demo');
});

/**
 * Reset the nodelist on the tracker server.
 */
router.post('/reset', (req, res) => {
	app.nodeList = new NodeList();
	app.transactionList = new TransactionList();
	res.json({success: true});
});

/**
 * Get the number of currently registered nodes and currently running nodes on the tracker server.
 */
router.get('/status', (req, res) => {
	res.json({registered: app.nodeList.getSize(), running:  app.nodeList.getRunning()});
});

function isPresent(arg) {
	return !!(arg || arg === 0 || arg === "" || arg === false);
}

/////////////////////////// TOPN stuff //////////////////////////

const sseClients = new sseMW.Topic();
// initial registration of SSE Client Connection
router.get('/topn/updates', function(req,res){
    const sseConnection = res.sseConnection;
    sseConnection.setup();
    sseClients.add(sseConnection);
} );

/**
 * send message to all registered SSE clients
 */
function updateSseClients() {
	const nodes = app.nodeList.getGraphNodes();
	const edges = app.transactionList.getGraphEdges();
    sseClients.forEach(sseConnection => sseConnection.send(
    	{nodes: nodes, edges: edges, numbers: app.transactionList.getNumbers()}));
}

/**
 * send a heartbeat signal to all SSE clients, once every interval seconds (or every 3 seconds if no interval is specified)
 * @param interval - interval in seconds
 */
function initHeartbeat(interval) {
    setInterval(() => {
            const msg = {"label":"The latest", "time":new Date()};
            updateSseClients( JSON.stringify(msg));
        }, interval?interval*1000:3000);
}
// initialize heartbeat at 10 second interval
initHeartbeat(10);

export default router;
