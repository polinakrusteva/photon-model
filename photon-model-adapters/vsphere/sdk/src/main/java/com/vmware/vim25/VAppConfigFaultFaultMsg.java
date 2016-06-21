
package com.vmware.vim25;

import javax.xml.ws.WebFault;


/**
 * This class was generated by the JAX-WS RI.
 * JAX-WS RI 2.2.9-b14002
 * Generated source version: 2.2
 * 
 */
@WebFault(name = "VAppConfigFaultFault", targetNamespace = "urn:vim25")
public class VAppConfigFaultFaultMsg
    extends Exception
{

    /**
     * Java type that goes as soapenv:Fault detail element.
     * 
     */
    private VAppConfigFault faultInfo;

    /**
     * 
     * @param faultInfo
     * @param message
     */
    public VAppConfigFaultFaultMsg(String message, VAppConfigFault faultInfo) {
        super(message);
        this.faultInfo = faultInfo;
    }

    /**
     * 
     * @param faultInfo
     * @param cause
     * @param message
     */
    public VAppConfigFaultFaultMsg(String message, VAppConfigFault faultInfo, Throwable cause) {
        super(message, cause);
        this.faultInfo = faultInfo;
    }

    /**
     * 
     * @return
     *     returns fault bean: com.vmware.vim25.VAppConfigFault
     */
    public VAppConfigFault getFaultInfo() {
        return faultInfo;
    }

}
