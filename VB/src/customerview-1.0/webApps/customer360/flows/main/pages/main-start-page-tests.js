/*
  Copyright (c) 2021, Oracle and/or its affiliates.
  The Universal Permissive License (UPL), Version 1.0
*/
define(['vb/test/TestUtils'], function(TestUtils) {
    'use strict';

    describe('webApps/customer360/flows/main/pages/main-start-page', function() {

        describe('GetData', function() {

            it('Test 1', async function() {
                const context = await TestUtils.getContext(this);
                const mocks = await TestUtils.getMocks(this);
                const results = await TestUtils.run(this, context, mocks);
                const expectations = await TestUtils.getExpectations(this);
                await TestUtils.verifyExpectations(this, results, expectations);
            });

        });

    });
});
