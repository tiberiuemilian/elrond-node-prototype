package network.elrond;

import network.elrond.account.AccountAddress;
import network.elrond.application.AppContext;
import network.elrond.core.ThreadUtil;
import network.elrond.core.Util;
import network.elrond.data.BootstrapType;

import java.math.BigInteger;
import java.text.SimpleDateFormat;
import java.util.Date;

public class NodeRunner {

    public static void main(String[] args) throws Exception {
        SimpleDateFormat sdfSource = new SimpleDateFormat(
                "yyyy-MM-dd HH.mm.ss");
        Util.changeLogsPath("logs/" + Util.getHostName() + " - " + sdfSource.format(new Date()));

        String nodeName = "elrond-node-2.1";
        Integer port = 4001;
        Integer masterPeerPort = 4000;
        String masterPeerIpAddress = "127.0.0.1";
        String nodeRunnerPrivateKey = "1111111111111111fa612ecafcfd145cc06c1fb64d7499ef34696ff16b82cbc2";
        //Reuploaded
        AppContext context = ContextCreator.createAppContext(nodeName, nodeRunnerPrivateKey, masterPeerIpAddress, masterPeerPort, port,
                BootstrapType.START_FROM_SCRATCH, nodeName);

        ElrondFacade facade = new ElrondFacadeImpl();

        Application application = facade.start(context);

        Thread thread = new Thread(() -> {

            do {

                AccountAddress address = AccountAddress.fromHexString(Util.TEST_ADDRESS);
               facade.send(address, BigInteger.TEN, application);
                System.out.println(facade.getBalance(address, application));
                ThreadUtil.sleep(2000);


            } while (true);

        });
        thread.start();

    }
}
