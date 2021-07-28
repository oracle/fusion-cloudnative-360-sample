/** 
Copyright (c) 2021, Oracle and/or its affiliates.
Licensed under the Universal Permissive License v 1.0 as shown at https://oss.oracle.com/licenses/upl.
**/
def customerViewService = adf.webServices.CustomerViewService
def customerViewServiceheaders = [:]
def loanDataheaders = [:]
loanDataheaders.'Content-Type' = 'application/vnd.oracle.adf.resourceitem+json'
customerViewServiceheaders.'Content-Type' = 'application/xml'
customerViewService.requestHTTPHeaders=customerViewServiceheaders
def objectName='Assets_c' //Change the object name to the name of the current object context
def key = Id.toString()
def loanService = adf.webServices.LoanDataService
loanService.requestHTTPHeaders=loanDataheaders
def queryParms = [:]
queryParms.expand='SundryCollection_c,AccountCollection_c,MotorVehiclesCollection_c,HomeAndInvestmentPropertiesCollection_c'  //change to the current list of child objects for this object context
queryParms.onlyData='true'
loanService.dynamicQueryParams = queryParms
def loanResponse = loanService.GET(objectName, key, queryParms)
def body = loanResponse
def response = customerViewService.POST(body)
throw new oracle.jbo.ValidationException(response.toString())