import {defineConfig} from '@playwright/test';
export default defineConfig({testDir:'./mods-ui-tests',workers:1,use:{baseURL:'http://127.0.0.1:5178',viewport:{width:1440,height:1000},headless:true},reporter:'list',timeout:30000});
