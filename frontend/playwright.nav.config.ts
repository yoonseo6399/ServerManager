import {defineConfig} from '@playwright/test';
export default defineConfig({testDir:'./nav-tests',outputDir:'./test-results/nav',workers:1,use:{baseURL:'http://127.0.0.1:5176',viewport:{width:1440,height:1000}},webServer:{command:'npm run dev -- --port 5176 --strictPort',url:'http://127.0.0.1:5176',reuseExistingServer:false},reporter:'list'});
