
package com.vmware.vim25;

import javax.xml.ws.WebFault;


/**
 * This class was generated by the JAX-WS RI.
 * JAX-WS RI 2.2.9-b14002
 * Generated source version: 2.2
 * 
 */
@WebFault(name = "LimitExceededFault", targetNamespace = "urn:vim25")
public class LimitExceededFaultMsg
    extends Exception
{

    /**
     * Java type that goes as soapenv:Fault detail element.
     * 
     */
    private LimitExceeded faultInfo;

    /**
     * 
     * @param faultInfo
     * @param message
     */
    public LimitExceededFaultMsg(String message, LimitExceeded faultInfo) {
        super(message);
        this.faultInfo = faultInfo;
    }

    /**
     * 
     * @param faultInfo
     * @param cause
     * @param message
     */
    public LimitExceededFaultMsg(String message, LimitExceeded faultInfo, Throwable cause) {
        super(message, cause);
        this.faultInfo = faultInfo;
    }

    /**
     * 
     * @return
     *     returns fault bean: com.vmware.vim25.LimitExceeded
     */
    public LimitExceeded getFaultInfo() {
        return faultInfo;
    }

}
