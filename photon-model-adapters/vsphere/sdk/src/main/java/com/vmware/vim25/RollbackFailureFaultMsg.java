
package com.vmware.vim25;

import javax.xml.ws.WebFault;


/**
 * This class was generated by the JAX-WS RI.
 * JAX-WS RI 2.2.9-b14002
 * Generated source version: 2.2
 * 
 */
@WebFault(name = "RollbackFailureFault", targetNamespace = "urn:vim25")
public class RollbackFailureFaultMsg
    extends Exception
{

    /**
     * Java type that goes as soapenv:Fault detail element.
     * 
     */
    private RollbackFailure faultInfo;

    /**
     * 
     * @param faultInfo
     * @param message
     */
    public RollbackFailureFaultMsg(String message, RollbackFailure faultInfo) {
        super(message);
        this.faultInfo = faultInfo;
    }

    /**
     * 
     * @param faultInfo
     * @param cause
     * @param message
     */
    public RollbackFailureFaultMsg(String message, RollbackFailure faultInfo, Throwable cause) {
        super(message, cause);
        this.faultInfo = faultInfo;
    }

    /**
     * 
     * @return
     *     returns fault bean: com.vmware.vim25.RollbackFailure
     */
    public RollbackFailure getFaultInfo() {
        return faultInfo;
    }

}
