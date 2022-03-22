//  Xilinx UltraRAM Simple Dual Port - Byte write.  This code implements 
//  a parameterizable UltraRAM block 1 Read and 1 write. 
//  when addra == addrb, old data will show at doutb 
module XilinxUram #(
  parameter AWIDTH  = 12,  // Address Width
  parameter NUM_COL = 9,   // Number of columns
  parameter DWIDTH  = 72   // Data Width
 ) ( 
    input clk,
    input reset,
    // flow write port
    input               w_valid,
    input [NUM_COL-1:0] w_mask,
    input [DWIDTH-1:0]  w_data,
    input [AWIDTH-1:0]  w_address,
    
    // stream read cmd
    input              r_cmd_valid,
    input [AWIDTH-1:0] r_cmd_address,
    output             r_cmd_ready,

    // stream read rsp
    output reg              r_rsp_valid,
    output reg [DWIDTH-1:0] r_rsp_data,
    input                   r_rsp_ready
   );

(* ram_style = "ultra" *)
reg [DWIDTH-1:0] mem[(1<<AWIDTH)-1:0];        // Memory Declaration

wire r_cmd_fire = r_cmd_ready && r_cmd_valid;

integer          i;
localparam CWIDTH = DWIDTH/NUM_COL;

// RAM : Both READ and WRITE have a latency of one
always @ (posedge clk) begin
    if(w_valid)
        for(i = 0;i<NUM_COL;i=i+1)
	        if(w_mask[i])
                mem[w_address][i*CWIDTH +: CWIDTH] <= w_data[i*CWIDTH +: CWIDTH];

    if(r_cmd_fire)
        r_rsp_data <= mem[r_cmd_address];
end

always @(posedge clk or posedge reset) begin
    if(reset) begin
        r_rsp_valid <= 1'b0;
    end else begin
        if(r_cmd_ready)
            r_rsp_valid <= r_cmd_valid;
    end
end

assign r_cmd_ready = r_rsp_ready;
endmodule                                     
					
			