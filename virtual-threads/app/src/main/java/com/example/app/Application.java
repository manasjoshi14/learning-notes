package com.example.app;

import com.example.app.virtualthreads.BasicVirtualThreadExample;
import com.example.app.virtualthreads.HttpServerExample;
import com.example.app.virtualthreads.ThreadPinningExample;
import com.example.app.virtualthreads.HttpLoadTester;
import com.example.app.virtualthreads.DatabaseOperationsExample;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Scanner;

/**
 * Main application class for virtual threads demonstration.
 */
public class Application {
    
    private static final Logger logger = LoggerFactory.getLogger(Application.class);
    
    /**
     * Main entry point for the application.
     */
    public static void main(String[] args) {
        logger.info("Virtual Threads Demo Application started with Java {}", System.getProperty("java.version"));
        
        // Check if any args are provided to run a specific example
        if (args.length > 0) {
            runExample(args[0]);
            return;
        }
        
        // Run in interactive mode
        if (System.console() != null) {
            runInteractive();
        } else {
            // When running non-interactively, run the basic example
            logger.info("Running in non-interactive mode. Running basic examples.");
            BasicVirtualThreadExample.runAllExamples();
        }
        
        logger.info("Application terminated");
    }
    
    /**
     * Run the application in interactive mode.
     */
    private static void runInteractive() {
        try (Scanner scanner = new Scanner(System.in)) {
            boolean exit = false;
            
            while (!exit) {
                printMenu();
                String choice = scanner.nextLine().trim();
                exit = processChoice(choice);
                
                if (!exit) {
                    logger.info("Press Enter to continue...");
                    scanner.nextLine();
                }
            }
        }
    }
    
    /**
     * Process the user's choice of example.
     */
    private static boolean processChoice(String choice) {
        switch (choice) {
            case "1":
                BasicVirtualThreadExample.runAllExamples();
                break;
            case "2":
                HttpServerExample.runExample();
                break;
            case "3":
                ThreadPinningExample.runExample();
                break;
            case "4":
                runLoadTest();
                break;
            case "5":
                DatabaseOperationsExample.runExample();
                break;
            case "0":
                logger.info("Exiting application");
                return true;
            default:
                logger.info("Invalid option '{}'. Please try again.", choice);
        }
        return false;
    }
    
    /**
     * Run the HTTP load tester with defaults.
     */
    private static void runLoadTest() {
        HttpLoadTester.runLoadTest(100, 500);
    }
    
    /**
     * Run a specific example based on a command line argument.
     */
    private static void runExample(String arg) {
        switch (arg) {
            case "basic":
            case "1":
                BasicVirtualThreadExample.runAllExamples();
                break;
            case "http":
            case "2":
                HttpServerExample.runExample();
                break;
            case "pinning":
            case "3":
                ThreadPinningExample.runExample();
                break;
            case "loadtest":
            case "4":
                runLoadTest();
                break;
            case "database":
            case "5":
                DatabaseOperationsExample.runExample();
                break;
            case "all":
                logger.info("Running all examples");
                BasicVirtualThreadExample.runAllExamples();
                DatabaseOperationsExample.runExample();
                ThreadPinningExample.runExample();
                HttpServerExample.runExample();
                break;
            default:
                logger.warn("Unknown example: '{}'. Running basic examples.", arg);
                BasicVirtualThreadExample.runAllExamples();
        }
    }
    
    /**
     * Prints the menu of available examples.
     */
    private static void printMenu() {
        logger.info("\n=== Virtual Threads Example Menu ===");
        logger.info("1. Basic Virtual Thread Examples");
        logger.info("2. HTTP Server Example");
        logger.info("3. Thread Pinning Example");
        logger.info("4. HTTP Load Test");
        logger.info("5. Database Operations Example");
        logger.info("0. Exit");
        logger.info("Choose an option (0-5): ");
    }
}
