package saml2webssotest.sp;

import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedList;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.commons.cli.BasicParser;
import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.CommandLineParser;
import org.apache.commons.cli.HelpFormatter;
import org.apache.commons.cli.Options;
import org.apache.commons.cli.ParseException;
import org.apache.log4j.BasicConfigurator;
import org.apache.log4j.PropertyConfigurator;
import org.eclipse.jetty.server.Server;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.w3c.dom.Document;
import org.w3c.dom.Node;

import com.gargoylesoftware.htmlunit.FailingHttpStatusCodeException;
import com.gargoylesoftware.htmlunit.HttpMethod;
import com.gargoylesoftware.htmlunit.WebClient;
import com.gargoylesoftware.htmlunit.WebRequest;
import com.gargoylesoftware.htmlunit.html.HtmlPage;
import com.gargoylesoftware.htmlunit.util.Cookie;
import com.gargoylesoftware.htmlunit.util.NameValuePair;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonSyntaxException;

import saml2webssotest.common.Interaction;
import saml2webssotest.common.InteractionDeserializer;
import saml2webssotest.common.MetadataDeserializer;
import saml2webssotest.common.StringPair;
import saml2webssotest.common.SAMLUtil;
import saml2webssotest.common.TestResult;
import saml2webssotest.common.TestRunnerUtil;
import saml2webssotest.common.TestStatus;
import saml2webssotest.common.TestSuite.TestCase;
import saml2webssotest.common.TestSuite.MetadataTestCase;
import saml2webssotest.common.standardNames.MD;
import saml2webssotest.common.standardNames.SAMLmisc;
import saml2webssotest.sp.mockIdPHandlers.SamlWebSSOHandler;
import saml2webssotest.sp.testsuites.SPTestSuite;
import saml2webssotest.sp.testsuites.SPTestSuite.ConfigTestCase;
import saml2webssotest.sp.testsuites.SPTestSuite.LoginTestCase;
import saml2webssotest.sp.testsuites.SPTestSuite.RequestTestCase;

/**
 * This is the main class that is used to run the SP test. It will handle the
 * command-line arguments appropriately and run the test(s).
 * 
 * @author RiaasM
 * 
 */
public class SPTestRunner {
	/**
	 * Logger for this class
	 */
	private static final Logger logger = LoggerFactory.getLogger(SPTestRunner.class);
	private static final String logFile = "slf4j.properties";
	/**
	 * The package where all test suites can be found, relative to the package containing this class.
	 */
	private static String testSuitesPackage = "testsuites";
	/**
	 * The test suite that is being run
	 */
	private static SPTestSuite testsuite;
	/**
	 * Contains the SP configuration
	 */
	private static SPConfiguration spConfig;
	/**
	 * Contains the SAML Request that was retrieved by the mock IdP
	 */
	private static String samlRequest;
	/**
	 * Contains the SAML binding that was recognized by the mock IdP
	 */
	private static String samlRequestBinding;
	/**
	 * Contains the HTTP request made in order to send the Response to the target SP
	 */
	private static WebRequest sentResponse;
	/**
	 * Contains the ACS URL to which the mock IdP should send its Response
	 */
	private static URL applicableACSURL;
	/**
	 * Contains the binding the mock IdP should use when sending its Response to the ACS 
	 */
	private static String applicableACSBinding;
	/**
	 * Contains the mock IdP server
	 */
	private static Server mockIdP;
	/**
	 * The browser which will be used to connect to the SP
	 */
	private static final WebClient browser = new WebClient();
	
	/**
	 * Contains the command-line options
	 */
	private static CommandLine command;

	public static void main(String[] args) {
		
		// initialize logging with properties file if it exists, basic config otherwise
		if(Files.exists(Paths.get(logFile))){
			PropertyConfigurator.configure(logFile);
		}
		else{
			BasicConfigurator.configure();
		}
		
		// define the command-line options
		Options options = new Options();
		options.addOption("h", "help", false, "Print this help message");
		options.addOption("i", "insecure", false,"Do not verify HTTPS server certificates");
		options.addOption("c", "spconfig", true,"The name of the properties file containing the configuration of the target SP");
		options.addOption("l", "listTestcases", false,"List all the test cases");
		options.addOption("L", "listTestsuites", false,"List all the test suites");
		options.addOption("m", "metadata", false,"Display the mock IdP metadata");
		options.addOption("T", "testsuite", true,"Specifies the test suite from which you wish to run a test case");
		options.addOption("t","testcase",true,"The name of the test case you wish to run. If omitted, all test cases from the test suite are run");

		LinkedList<TestResult> testresults = new LinkedList<TestResult>();
		try {
			// parse the command-line arguments
			CommandLineParser parser = new BasicParser();

			// parse the command line arguments
			command = parser.parse(options, args);

			// show the help message
			if (command.hasOption("help")) {
				new HelpFormatter().printHelp("SPTestRunner", options, true);
				System.exit(0);
			}

			// list the test suites, if necessary
			if (command.hasOption("listTestsuites")) {
				TestRunnerUtil.listTestSuites(SPTestRunner.class.getPackage().getName() + "." + testSuitesPackage);
				System.exit(0);
			}

			if (command.hasOption("testsuite")) {
				// load the test suite
				String ts_string = command.getOptionValue("testsuite");
				Class<?> ts_class = Class.forName(SPTestRunner.class.getPackage().getName() + "." + testSuitesPackage + "." + ts_string);
				Object testsuiteObj = ts_class.newInstance();
				if (testsuiteObj instanceof SPTestSuite) {
					testsuite = (SPTestSuite) testsuiteObj;

					// list the test cases, if necessary
					if (command.hasOption("listTestcases")) {
						TestRunnerUtil.listTestCases(testsuite);
						System.exit(0);
					}

					// show mock IdP metadata
					if (command.hasOption("metadata")) {
						TestRunnerUtil.outputMockedMetadata(testsuite);
						System.exit(0);
					}

					// configure the browser that will be used during testing
					browser.getOptions().setRedirectEnabled(true);
					if (command.hasOption("insecure")) {
						browser.getOptions().setUseInsecureSSL(true);
					}
					
					// load target SP config
					if (command.hasOption("spconfig")) {
						spConfig = new GsonBuilder()
											.registerTypeAdapter(Document.class, new MetadataDeserializer())
											.registerTypeAdapter(Interaction.class, new InteractionDeserializer())
											.create()
											.fromJson(Files.newBufferedReader(Paths.get(command.getOptionValue("spconfig")),Charset.defaultCharset()), SPConfiguration.class); 
						//new SPConfiguration(command.getOptionValue("spconfig"));
					} else {
						// use empty SP configuration
						spConfig = new SPConfiguration();
					}
					
					// initialize the mocked server
					mockIdP = TestRunnerUtil.newMockServer(testsuite.getMockServerURL(), new SamlWebSSOHandler());
					// start the mock IdP
					mockIdP.start();

					// load the requested test case(s)
					String testcaseName = command.getOptionValue("testcase");
					
					// get the test case(s) we want to run
					ArrayList<TestCase> testcases = TestRunnerUtil.getTestCases(testsuite, testcaseName);

					// run the test case(s) from the test suite
					for(TestCase testcase: testcases){
						TestStatus status = runTest(testcase);
						
						TestResult result = new TestResult(status, testcase.getResultMessage());
						result.setName(testcase.getClass().getSimpleName());
						result.setDescription(testcase.getDescription());
						// add this test result to the list of test results
						testresults.add(result);
					}
					TestRunnerUtil.outputTestResults(testresults);
				} else {
					logger.error("Provided class was not a TestSuite");
				}
			}
		} catch (ClassNotFoundException e) {
			// test suite or case could not be found
			if (testsuite == null)
				logger.error("Test suite could not be found", e);
			else
				logger.error("Test case could not be found", e);
			testresults.add(new TestResult(TestStatus.CRITICAL, ""));
		} catch (ClassCastException e) {
			logger.error("The test suite or case was not an instance of TestSuite", e);
		} catch (IOException e) {
			logger.error("I/O error occurred when creating HTTP server", e);
		} catch (ParseException e) {
			logger.error("Parsing of the command-line arguments has failed", e);
		} catch (JsonSyntaxException jsonExc) {
			logger.error("The JSON configuration file did not have the correct syntax", jsonExc);
		} catch (Exception e) {
			logger.error("The test(s) could not be run", e);
		} finally {
			// stop the mock IdP
			try {
				if (mockIdP!= null && mockIdP.isStarted()){
					mockIdP.stop();
				}
			} catch (Exception e) {
				logger.error("The mock IdP could not be stopped", e);
			}
		}
	}

	/**
	 * Run the test case that is provided.
	 * 
	 * @param testcase
	 *            represents the test case that needs to be run
	 * @param spconfig
	 *            contains the configuration required to run the test for the
	 *            target SP
	 * @return a string representing the test result in JSON format.
	 */
	private static TestStatus runTest(TestCase testcase) {
		logger.info("Running testcase: "+ testcase.getClass().getSimpleName());
		
		
		// run the test case according to what type of test case it is
		if (testcase instanceof ConfigTestCase) {
			ConfigTestCase cfTestcase = (ConfigTestCase) testcase;
			/**
			 * Check the SP's metadata according to the specifications of the
			 * test case and return the status of the test
			 */
			return cfTestcase.checkConfig(spConfig);
		}
		else if (testcase instanceof MetadataTestCase) {
			// Retrieve the SP Metadata from target SP configuration
			Document metadata = spConfig.getMetadata();
			MetadataTestCase mdTestcase = (MetadataTestCase) testcase;
			/**
			 * Check the SP's metadata according to the specifications of the
			 * test case and return the status of the test
			 */
			return mdTestcase.checkMetadata(metadata);
		} else if (testcase instanceof RequestTestCase) {
			RequestTestCase reqTC = (RequestTestCase) testcase;
			// make the SP send the AuthnRequest by starting an SP-initiated login attempt
			try {
				initiateLoginAttempt(true);
				//TestRunnerUtil.interactWithPage(browser.getPage(spConfig.getStartPage()), spConfig.getPreLoginInteractions());
				if (samlRequest != null && !samlRequest.isEmpty()) {
					logger.debug("Testing the AuthnRequest");
					logger.trace(samlRequest);
					/**
					 * Check the SAML Request according to the specifications of the
					 * test case and return the status of the test
					 */
					TestStatus requestResult = reqTC.checkRequest(samlRequest,samlRequestBinding);
					resetBrowser();
					return requestResult;
				} else {
					logger.error("Could not retrieve the SAML Request that was sent by the target SP");
					return TestStatus.CRITICAL;
				}
			} catch (FailingHttpStatusCodeException e) {
				logger.error("The start page returned a failing HTTP status code", e);
				return TestStatus.CRITICAL;
			}
		} else if (testcase instanceof LoginTestCase) {
			LoginTestCase loginTC = (LoginTestCase) testcase;
			/**
			 * Check if login attempts are handled correctly
			 */
			TestStatus loginResult = loginTC.checkLogin();
			resetBrowser();
			return loginResult;
		} else {
			logger.error("Trying to run an unknown type of test case");
			return null;
		}
	}

	/**
	 * Initiate a login attempt at the target SP. 
	 * 
	 * This will initiate the login process by causing the target SP to send an 
	 * AuthnRequest (if SP-initiated), storing the AuthnRequest that was received 
	 * by the mock IdP (if SP-initiated) and figuring out which ACS URL and binding 
	 * the mock IdP should use.
	 * 
	 * @param spInitiated defines whether the login attempt should be SP-initiated 
	 */
	public static void initiateLoginAttempt(boolean spInitiated){
		Node applicableACS;
		// determine the ACS location and binding, depending on the received SAML Request
		try {
		if(spInitiated){
			// retrieve the login page, thereby sending the AuthnRequest to the mock IdP
				TestRunnerUtil.interactWithPage(browser.getPage(spConfig.getStartPage()), spConfig.getPreLoginInteractions());
			// check if the saml request has correctly been retrieved by the mock IdP 
			// if not, most likely caused by trying to use artifact binding
			if (samlRequest == null || samlRequest.isEmpty()) {
				logger.error("Could not retrieve the SAML request after SP-initiated login attempt");
			}
			applicableACS = spConfig.getApplicableACS(SAMLUtil.fromXML(samlRequest));
		}
		else{
			// go directly to the IdP page without an AuthnRequest (for idp-initiated authentication)
			browser.getPage(testsuite.getMockServerURL().toString());		
			applicableACS = spConfig.getApplicableACS(null);
		}
		
		// try to retrieve the location and binding of the ACS where this should be sent from the request
		applicableACSURL = new URL(applicableACS.getAttributes().getNamedItem(MD.LOCATION).getNodeValue());
		applicableACSBinding = applicableACS.getAttributes().getNamedItem(MD.BINDING).getNodeValue();
		
		} catch (FailingHttpStatusCodeException e){
			logger.error("Could not retrieve browser page for the LoginTestCase", e);
		} catch (MalformedURLException e) {
			logger.error("The URL for the start page was malformed", e);
		} catch (IOException e) {
			logger.error("An I/O exception occurred while trying to access the start page", e);
		}
	}
	/**
	 * Finish trying to log in to the target SP with the mock IdP returning the provided SAML Response.
	 * 
	 * Note that you should have first initiated the login attempt with initiateLogin() in order for
	 * the mock IdP to know which ACS URL and binding should be used 
	 * 
	 * 
	 * @param response is the SAML Response that should be returned by the mock IdP
	 * @return a Boolean object with value true if the login attempt was successful, false if 
	 * the login attempt failed and null if the login attempt could not be completed
	 */
	public static Boolean completeLoginAttempt(String response){
		// start login attempt with target SP
		try {
			// create HTTP request to send the SAML response to the SP's ACS url
			sentResponse = new WebRequest(applicableACSURL, HttpMethod.POST);
			ArrayList<NameValuePair> postParameters = new ArrayList<NameValuePair>();
			NameValuePair samlresponse;
			// set the SAML URL parameter according to the requested binding
			if (applicableACSBinding.equalsIgnoreCase(SAMLmisc.BINDING_HTTP_POST)){
				samlresponse = new NameValuePair(SAMLmisc.URLPARAM_SAMLRESPONSE_POST, SAMLUtil.encodeSamlMessageForPost(response));
			}
			else if (applicableACSBinding.equalsIgnoreCase(SAMLmisc.BINDING_HTTP_ARTIFACT)){
				// TODO: support artifact binding
				logger.debug("Response needs to be sent with Artifact binding, this is not yet supported");
				return null;
			}
			else{
				logger.error("An invalid binding was requested for sending the Response to the SP");
				return null;
			}
			postParameters.add(samlresponse);
			sentResponse.setRequestParameters(postParameters);
			
			logger.debug("Sending SAML Response to the SP");
			logger.trace(response);
			// send the SAML response to the SP
			HtmlPage responsePage = browser.getPage(sentResponse);
			
			logger.trace("The received page:\n"+responsePage.getWebResponse().getContentAsString());
			
			// the login succeeded when all configured matches are found
			if (checkLoginHTTPStatusCode(responsePage) 
					&& checkLoginURL(responsePage) 
					&& checkLoginContent(responsePage) 
					&& checkLoginCookies(responsePage)) {
				return new Boolean(true);
			}
			else{
				return new Boolean(false);
			}
		} catch (FailingHttpStatusCodeException e){
			logger.error("Could not retrieve browser page for the LoginTestCase", e);
			return null;
		} catch (IOException e) {
			logger.error("Could not execute HTTP request for the LoginTestCase", e);
			return null;
		}
	}
	
	/**
	 * Retrieves the browser that is used by the test runner. 
	 * 
	 * This allows performing additional actions in the browser, if required by 
	 * any test case. 
	 * 
	 * @return the WebClient used as browser by the test runner.
	 */
	public static WebClient getBrowser(){
		return browser;
	}
	
	/**
	 * Resets the WebClient object used as browser.
	 * 
	 * This will close all browser windows and clear all cookies and cache
	 */
	public static void resetBrowser() {
		// close the browser windows after each test case
		browser.getCache().clear();
		browser.getCookieManager().clearCookies();
		browser.closeAllWindows();
	}

	/**
	 * Retrieves the SAML Request that was received from the SP
	 * 
	 * This is set from the Handler that processes the SP's login attempt
	 * on the mock IdP so it should only be retrieved after a login 
	 * attempt has been initiated
	 * 
	 * @param request is the SAML Request
	 */
	public static String getSamlRequest() {
		return samlRequest;
	}
	/**
	 * Set the SAML Request that was received from the SP
	 * 
	 * This is set from the Handler that processes the SP's login attempt
	 * on the mock IdP.
	 * 
	 * @param request is the SAML Request
	 */
	public static void setSamlRequest(String request) {
		samlRequest = request;
	}

	/**
	 * Set the SAML Binding that the SP has used to send its AuthnRequest
	 * 
	 * This is set from the Handler that processes the SP's login attempt
	 * on the mock IdP.
	 * 
	 * @param binding is the name of the SAML Binding
	 */
	public static void setSamlRequestBinding(String binding) {
		samlRequestBinding = binding;
	}

	/**
	 * Retrieve the SPConfiguration object containing the target SP configuration info
	 * 
	 * @return the SPConfiguration object used in this test
	 */
	public static SPConfiguration getSPConfig() {
		return spConfig;
	}
	
	/**
	 * Retrieve the HTTP request used to send the Response to the target SP (might not be needed)
	 * 
	 * @return the WebRequest object used to send the Response
	 *//*
	public static WebRequest getSentResponse() {
		return sentResponse;
	}*/

	private static boolean checkLoginHTTPStatusCode(HtmlPage page){
		// check the HTTP Status code of the page to see if the login was successful
		if (spConfig.getLoginStatuscode() == 0) {
			// do not match against status code
			return true;
		} 
		else if (page.getWebResponse().getStatusCode() == spConfig.getLoginStatuscode()) {
			return true;
		}
		else{
			logger.debug("The page's HTTP status code did not match the expected HTTP status code");
			return false;
		}
	}

	private static boolean checkLoginURL(HtmlPage responsePage) {
		// check the URL of the page to see if the login was successful
		if (spConfig.getLoginURL() == null) {
			// do not match against URL
			return true;
		} else {
			URL responseURL = responsePage.getUrl();
			URL matchURL;
			try {
				matchURL = new URL(spConfig.getLoginURL());
			
				// check if the current location matches what we expect when we are
				// correctly logged in
				if (responseURL.equals(matchURL)) {
					return true;
				} else {
					logger.debug("Could not match the URL " + matchURL.toString()
							+ " against the returned page's URL "
							+ responseURL.toString());
					return false;
				}
			} catch (MalformedURLException e) {
				logger.debug("The expected URL " + spConfig.getLoginURL() + " is malformed");
				return false;
			}
		}
	}

	private static boolean checkLoginContent(HtmlPage responsePage) {
		// check if the page matches what we expect to see when we log in
		String page = responsePage.getWebResponse().getContentAsString();
		if (spConfig.getLoginContent() == null) {
			// do no match against page content
			return true;
		} else {
			String contentRegex = spConfig.getLoginContent();
			// compile the regex so it allows the dot character to also match new-line characters,
			// which is useful since this is a multi-line string
			Pattern regexP = Pattern.compile(contentRegex, Pattern.DOTALL);
			Matcher regexM = regexP.matcher(page);
			if (regexM.find()) {
				return true;
			} else {
				logger.debug("Could not match the following regex against the returned page:\n"+ contentRegex);
				return false;
			}
		}
	}

	private static boolean checkLoginCookies(HtmlPage responsePage) {
		// check the cookies
		if (spConfig.getLoginCookies().size() <= 0) {
			// do not check cookies
			return true;
		} else {
			ArrayList<StringPair> checkCookies = spConfig.getLoginCookies();
			Set<Cookie> sessionCookies = browser.getCookies(sentResponse.getUrl());

			// only check for cookies if we actually have some to match against
			if (checkCookies.size() > 0) {
				boolean found = false;
				// check if each user-supplied cookie name and value is
				// available
				for (StringPair checkCookie : checkCookies) {
					String name = checkCookie.getName();
					String value = checkCookie.getValue();
					// iterate through the session cookies to see if it contains
					// the the checked cookie
					for (Cookie sessionCookie : sessionCookies) {
						String cookieName = sessionCookie.getName();
						String cookieValue = sessionCookie.getValue();
						// compare the cookie names
						if (cookieName.equalsIgnoreCase(name)) {
							// if no value given, you don't need to compare it
							if (value == null || value.isEmpty()) {
								found = true;
								break;
							} else {
								if (cookieValue.equalsIgnoreCase(value)) {
									found = true;
									break;
								}
							}
						}
					}
					// this cookie could not be found, so we could not find a match
					if (!found) {
						logger.debug("Could not match the following cookie against the returned page:\n"+ checkCookie.getName()+ ", "+ checkCookie.getValue());
						return false;
					}
				}
				// you got through all cookies so all cookies matched
				return true;
			}
			else{
				// we could not find any cookies in the page, so this failed our check
				return false;
			}
		}
	}
}
