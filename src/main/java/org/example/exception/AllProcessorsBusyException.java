package org.example.exception;

public class AllProcessorsBusyException extends Exception{
    public AllProcessorsBusyException(String errorMessage){
        super(errorMessage);
    }
}
