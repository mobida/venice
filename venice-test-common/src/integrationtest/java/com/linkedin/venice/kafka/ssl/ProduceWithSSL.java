package com.linkedin.venice.kafka.ssl;

import com.linkedin.venice.client.store.AvroGenericStoreClient;
import com.linkedin.venice.client.store.ClientConfig;
import com.linkedin.venice.client.store.ClientFactory;
import com.linkedin.venice.controllerapi.ControllerClient;
import com.linkedin.venice.controllerapi.VersionCreationResponse;
import com.linkedin.venice.hadoop.VenicePushJob;
import com.linkedin.venice.integration.utils.ServiceFactory;
import com.linkedin.venice.integration.utils.VeniceClusterWrapper;
import com.linkedin.venice.utils.DataProviderUtils;
import com.linkedin.venice.utils.KafkaSSLUtils;
import com.linkedin.venice.utils.SslUtils;
import com.linkedin.venice.utils.TestPushUtils;
import com.linkedin.venice.utils.TestUtils;
import com.linkedin.venice.utils.Time;
import com.linkedin.venice.writer.VeniceWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.charset.Charset;
import java.util.HashMap;
import java.util.Properties;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import org.apache.avro.Schema;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.io.Text;
import org.apache.hadoop.security.Credentials;
import org.apache.hadoop.security.UserGroupInformation;
import org.apache.kafka.common.config.SslConfigs;
import org.testng.Assert;
import org.testng.annotations.AfterClass;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

import static com.linkedin.venice.utils.TestPushUtils.createStoreForJob;
import static com.linkedin.venice.utils.TestPushUtils.getTempDataDirectory;
import static com.linkedin.venice.utils.TestPushUtils.sslH2VProps;
import static com.linkedin.venice.utils.TestPushUtils.writeSimpleAvroFileWithUserSchema;


public class ProduceWithSSL {
  private VeniceClusterWrapper cluster;

  @BeforeClass
  public void setup() {
    cluster = ServiceFactory.getVeniceClusterWithKafkaSSL(false);
  }

  @AfterClass
  public void cleanup() {
    cluster.close();
  }

  @Test(timeOut = 60 * Time.MS_PER_SECOND)
  public void testVeniceWriterSupportSSL()
      throws ExecutionException, InterruptedException {
    String storeName = TestUtils.getUniqueString("testVeniceWriterSupportSSL");
    cluster.getNewStore(storeName);
    VersionCreationResponse response = cluster.getNewVersion(storeName, 1000);
    Assert.assertFalse(response.isError());
    int version = response.getVersion();
    String topic = response.getKafkaTopic();
    VeniceWriter<String, String, byte[]> writer = cluster.getSslVeniceWriter(topic);
    String testKey = "key";
    String testVal = "value";
    writer.broadcastStartOfPush(new HashMap<>());
    writer.put(testKey, testVal, 1);
    writer.broadcastEndOfPush(new HashMap<>());

    // Wait for storage node to finish consuming, and new version to be activated
    String controllerUrl = cluster.getAllControllersURLs();
    ControllerClient controllerClient = new ControllerClient(cluster.getClusterName(), controllerUrl);
    TestUtils.waitForNonDeterministicCompletion(30, TimeUnit.SECONDS, () -> {
      int currentVersion = controllerClient.getStore(storeName).getStore().getCurrentVersion();
      return currentVersion == version;
    });

    AvroGenericStoreClient<String, CharSequence> storeClient = ClientFactory.getAndStartGenericAvroClient(
        ClientConfig.defaultGenericClientConfig(storeName)
            .setVeniceURL(cluster.getRandomRouterURL())
            .setSslEngineComponentFactory(SslUtils.getLocalSslFactory())
    );

    Assert.assertEquals(storeClient.get(testKey).get().toString(), testVal);
    writer.close();
    controllerClient.close();
  }
  private byte[] readFile(String path)
      throws IOException {
    File file = new File(path);
    try (FileInputStream fis = new FileInputStream(file)) {
      byte[] data = new byte[(int)file.length()];
      fis.read(data);
      return data;
    }
  }

  @Test(dataProvider = "True-and-False", dataProviderClass = DataProviderUtils.class, timeOut = 60 * Time.MS_PER_SECOND)
  public void testKafkaPushJobSupportSSL(boolean isOpenSSLEnabled)
      throws Exception {
    VeniceClusterWrapper cluster = this.cluster;
    try {
      if (isOpenSSLEnabled) {
        cluster = ServiceFactory.getVeniceClusterWithKafkaSSL(true);
      }
      File inputDir = getTempDataDirectory();
      String storeName = TestUtils.getUniqueString("store");
      Schema recordSchema = writeSimpleAvroFileWithUserSchema(inputDir);
      String inputDirPath = "file://" + inputDir.getAbsolutePath();
      Properties props = sslH2VProps(cluster, inputDirPath, storeName);

      String keyStorePropertyName = "ssl.identity";
      String trustStorePropertyName = "ssl.truststore";
      String keyStorePwdPropertyName = "ssl.identity.keystore.password";
      String keyPwdPropertyName="ssl.identity.key.password";

      props.setProperty(VenicePushJob.SSL_KEY_STORE_PROPERTY_NAME, keyStorePropertyName);
      props.setProperty(VenicePushJob.SSL_TRUST_STORE_PROPERTY_NAME,trustStorePropertyName);
      props.setProperty(VenicePushJob.SSL_KEY_STORE_PASSWORD_PROPERTY_NAME,keyStorePwdPropertyName);
      props.setProperty(VenicePushJob.SSL_KEY_PASSWORD_PROPERTY_NAME,keyPwdPropertyName);

      // put cert into hadoop user credentials.
      Properties sslProps = KafkaSSLUtils.getLocalCommonKafkaSSLConfig();
      byte[] keyStoreCert = readFile(sslProps.getProperty(SslConfigs.SSL_KEYSTORE_LOCATION_CONFIG));
      byte[] trustStoreCert = readFile(sslProps.getProperty(SslConfigs.SSL_TRUSTSTORE_LOCATION_CONFIG));
      Credentials credentials = new Credentials();
      credentials.addSecretKey(new Text(keyStorePropertyName), keyStoreCert);
      credentials.addSecretKey(new Text(trustStorePropertyName), trustStoreCert);
      credentials.addSecretKey(new Text(keyStorePwdPropertyName), sslProps.getProperty(SslConfigs.SSL_KEYSTORE_PASSWORD_CONFIG).getBytes(
          Charset.forName("UTF-8")));
      credentials.addSecretKey(new Text(keyPwdPropertyName), sslProps.getProperty(SslConfigs.SSL_KEY_PASSWORD_CONFIG).getBytes(
          Charset.forName("UTF-8")));
      UserGroupInformation.getCurrentUser().addCredentials(credentials);
      // Setup token file
      String filePath = getTempDataDirectory().getAbsolutePath() + "/testHadoopToken";
      credentials.writeTokenStorageFile(new Path(filePath), new Configuration());
      System.setProperty(UserGroupInformation.HADOOP_TOKEN_FILE_LOCATION, filePath);

      Assert.assertEquals(System.getProperty(UserGroupInformation.HADOOP_TOKEN_FILE_LOCATION), filePath);

      createStoreForJob(cluster, recordSchema, props).close();
      String controllerUrl = cluster.getAllControllersURLs();
      ControllerClient controllerClient = new ControllerClient(cluster.getClusterName(), controllerUrl);
      Assert.assertEquals(controllerClient.getStore(storeName).getStore().getCurrentVersion(), 0, "Push has not been start, current should be 0");
      TestPushUtils.runPushJob("Test push job", props);
      TestUtils.waitForNonDeterministicCompletion(30, TimeUnit.SECONDS, () -> {
        int currentVersion = controllerClient.getStore(storeName).getStore().getCurrentVersion();
        return currentVersion == 1;
      });
    } finally {
      if (isOpenSSLEnabled) {
        cluster.close();
      }
    }
  }
}
