
package com.vmware.vim25;

import javax.xml.ws.WebFault;


/**
 * This class was generated by the JAX-WS RI.
 * JAX-WS RI 2.2.9-b14002
 * Generated source version: 2.2
 * 
 */
@WebFault(name = "InvalidDatastorePathFault", targetNamespace = "urn:vim25")
public class InvalidDatastorePathFaultMsg
    extends Exception
{

    /**
     * Java type that goes as soapenv:Fault detail element.
     * 
     */
    private InvalidDatastorePath faultInfo;

    /**
     * 
     * @param faultInfo
     * @param message
     */
    public InvalidDatastorePathFaultMsg(String message, InvalidDatastorePath faultInfo) {
        super(message);
        this.faultInfo = faultInfo;
    }

    /**
     * 
     * @param faultInfo
     * @param cause
     * @param message
     */
    public InvalidDatastorePathFaultMsg(String message, InvalidDatastorePath faultInfo, Throwable cause) {
        super(message, cause);
        this.faultInfo = faultInfo;
    }

    /**
     * 
     * @return
     *     returns fault bean: com.vmware.vim25.InvalidDatastorePath
     */
    public InvalidDatastorePath getFaultInfo() {
        return faultInfo;
    }

}
