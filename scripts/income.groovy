/** 
Copyright (c) 2021, Oracle and/or its affiliates.
Licensed under the Universal Permissive License v 1.0 as shown at https://oss.oracle.com/licenses/upl.
**/
def customerViewService = adf.webServices.CustomerViewService
def headers = [:] 
def loanDataheaders = [:]
loanDataheaders.'Content-Type' = 'application/vnd.oracle.adf.resourceitem+json'
headers.'Content-Type' = 'application/xml'
customerViewService.requestHTTPHeaders=headers 
def objectName='LoanApplicantIncome_c'
def key = Id.toString()          
def loanService = adf.webServices.LoanDataService
loanService.requestHTTPHeaders=loanDataheaders 
def queryParms = [:] 
queryParms.expand='OtherIncomeCollection_c,SalaryCollection_c'
queryParms.onlyData='true'
loanService.dynamicQueryParams = queryParms 
def loanResponse = loanService.GET(objectName, key, queryParms) 
def body = loanResponse
def response = customerViewService.POST(body) 
throw new oracle.jbo.ValidationException(response.toString())    